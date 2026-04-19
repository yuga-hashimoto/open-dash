package com.opendash.app.voice.pipeline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.AssistantSession
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.data.db.MessageDao
import com.opendash.app.data.db.MessageEntity
import com.opendash.app.data.db.SessionDao
import com.opendash.app.data.db.SessionEntity
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.voice.fastpath.FastPathRouter
import com.opendash.app.voice.metrics.LatencyRecorder
import com.opendash.app.voice.stt.AndroidSttProvider
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.SttResult
import com.opendash.app.voice.tts.TextToSpeech
import com.opendash.app.service.VoiceService
import com.opendash.app.voice.wakeword.WakeWordDetector
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class VoicePipeline(
    private val context: Context,
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val router: ConversationRouter,
    private val toolExecutor: ToolExecutor,
    private val moshi: Moshi,
    private val preferences: AppPreferences,
    private val sessionDao: SessionDao? = null,
    private val messageDao: MessageDao? = null,
    private val wakeWordDetector: WakeWordDetector? = null,
    private val fastPathRouter: FastPathRouter? = null,
    private val latencyRecorder: LatencyRecorder = LatencyRecorder(),
    private val fastPathLlmPolisher: FastPathLlmPolisher = FastPathLlmPolisher()
) {
    /** Exposed for diagnostics / Settings debug screen. */
    fun latencySummary() = latencyRecorder.summarize()
    private val _state = MutableStateFlow<VoicePipelineState>(VoicePipelineState.Idle)
    val state: StateFlow<VoicePipelineState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    /**
     * The sentence currently being spoken by the TTS engine. Empty while
     * idle or when the active provider does not emit per-chunk progress.
     * The UI surfaces this for a karaoke-style rolling display during
     * [VoicePipelineState.Speaking], falling back to [lastResponse] when
     * empty so non-chunking providers (OpenAI, ElevenLabs, VOICEVOX, Piper)
     * still show the full reply.
     */
    val currentSpokenText: StateFlow<String> = tts.currentChunk

    private var currentSession: AssistantSession? = null
    private val conversationHistory = mutableListOf<AssistantMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchdogJob: Job? = null
    private var persistedSessionId: String? = null
    private val errorClassifier = ErrorClassifier()

    private fun currentProviderKind(): ErrorClassifier.ProviderKind {
        val active = router.activeProvider.value ?: return ErrorClassifier.ProviderKind.UNKNOWN
        return if (active.capabilities.isLocal) ErrorClassifier.ProviderKind.LOCAL
        else ErrorClassifier.ProviderKind.REMOTE
    }

    init {
        // Lazy restore: actual load happens on first startListening() to avoid blocking init
        scope.launch { tryRestoreLastSession() }
    }

    private suspend fun tryRestoreLastSession() {
        val resume = preferences.observe(PreferenceKeys.RESUME_LAST_SESSION).first() ?: false
        if (!resume || sessionDao == null || messageDao == null) return
        try {
            val session = sessionDao.getAll().firstOrNull() ?: return
            val messages = messageDao.getBySessionId(session.id)
            if (messages.isEmpty()) return
            val restored = messages.mapNotNull { e ->
                when (e.role) {
                    "user" -> AssistantMessage.User(content = e.content)
                    "assistant" -> AssistantMessage.Assistant(content = e.content)
                    "system" -> AssistantMessage.System(content = e.content)
                    else -> null
                }
            }
            conversationHistory.clear()
            conversationHistory.addAll(restored)
            persistedSessionId = session.id
            Timber.d("Restored ${restored.size} messages from last session ${session.id}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore last session")
        }
    }

    private suspend fun persistUserMessage(content: String) {
        if (sessionDao == null || messageDao == null) return
        val sessionId = ensurePersistedSessionId() ?: return
        try {
            messageDao.insert(
                MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist user message")
        }
    }

    private suspend fun persistAssistantMessage(content: String) {
        if (sessionDao == null || messageDao == null) return
        val sessionId = ensurePersistedSessionId() ?: return
        try {
            messageDao.insert(
                MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "assistant",
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist assistant message")
        }
    }

    private suspend fun ensurePersistedSessionId(): String? {
        if (sessionDao == null) return null
        persistedSessionId?.let { return it }
        val providerId = currentSession?.providerId ?: "unknown"
        val newId = java.util.UUID.randomUUID().toString()
        try {
            sessionDao.insert(SessionEntity(id = newId, providerId = providerId, createdAt = System.currentTimeMillis()))
            persistedSessionId = newId
        } catch (e: Exception) {
            Timber.w(e, "Failed to create persisted session")
        }
        return persistedSessionId
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val MAX_TOOL_ROUNDS = 10
        private const val WATCHDOG_TIMEOUT_MS = 5 * 60 * 1000L
        private const val CONTINUOUS_MODE_DELAY_MS = 500L
        private const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L
        private const val PENDING_CALL_TIMEOUT_MS = 30_000L
    }

    fun startWakeWordListening() {
        val detector = wakeWordDetector ?: return
        _state.value = VoicePipelineState.WakeWordListening
        detector.start {
            Timber.d("Wake word detected!")
            scope.launch { startListening() }
        }
        startWatchdog()
    }

    fun stopWakeWordListening() {
        wakeWordDetector?.stop()
        cancelWatchdog()
        _state.value = VoicePipelineState.Idle
    }

    suspend fun startListening() {
        // Barge-in handling
        if (_state.value is VoicePipelineState.Speaking) {
            val bargeInEnabled = preferences.observe(PreferenceKeys.BARGE_IN_ENABLED).first() ?: true
            if (!bargeInEnabled) {
                Timber.d("Barge-in disabled, ignoring mic tap during speech")
                return
            }
            tts.stop()
        }
        stt.stopListening()
        _partialText.value = ""
        _lastResponse.value = ""

        // Apply STT settings from preferences (the binding is lost across calls)
        applySttPreferences()
        applyTtsLanguagePreference()

        // Pause wake word detection to release microphone (OpenClaw broadcast pattern)
        VoiceService.pauseHotword(context)

        requestAudioFocus()
        playListeningBeep()
        // Wait for beep to finish and mic to be fully released
        delay(500)

        _state.value = VoicePipelineState.Listening
        resetWatchdog()

        var finalText = ""
        latencyRecorder.startSpan(LatencyRecorder.Span.STT_DURATION)
        try {
            stt.startListening().collect { result ->
                when (result) {
                    is SttResult.Partial -> {
                        _partialText.value = result.text
                    }
                    is SttResult.Final -> {
                        finalText = result.text
                        _partialText.value = result.text
                        latencyRecorder.endSpan(LatencyRecorder.Span.STT_DURATION)
                    }
                    is SttResult.Error -> {
                        latencyRecorder.endSpan(LatencyRecorder.Span.STT_DURATION)
                        Timber.w("STT error: ${result.message}")
                        // Every STT-layer failure (NO_MATCH, SPEECH_TIMEOUT,
                        // NETWORK, CLIENT, SERVER, RECOGNIZER_BUSY …) drops
                        // us back to Idle silently. All of these indicate
                        // either "user never spoke" or "transient
                        // transcription hiccup" — neither warrants the
                        // intrusive red "Sorry, I didn't catch that"
                        // overlay on the ambient Home. The user just tries
                        // again by saying the wake word.
                        abandonAudioFocus()
                        resumeWakeWord()
                        _state.value = VoicePipelineState.Idle
                        return@collect
                    }
                }
            }
        } catch (e: Exception) {
            latencyRecorder.endSpan(LatencyRecorder.Span.STT_DURATION)
            Timber.e(e, "STT failed")
            val recovery = errorClassifier.classify(e.message, e, kind = currentProviderKind())
            _lastResponse.value = recovery.userSpokenMessage
            _state.value = VoicePipelineState.Error(recovery.userSpokenMessage)
            abandonAudioFocus()
            delay(2000)
            resumeWakeWord()
            _state.value = VoicePipelineState.Idle
            return
        }

        Timber.d("STT finalText='$finalText' (blank=${finalText.isBlank()})")
        if (finalText.isNotBlank()) {
            processUserInput(finalText)
        } else {
            abandonAudioFocus()
            resumeWakeWord()
            Timber.d("No speech detected, returning to Idle")
            _state.value = VoicePipelineState.Idle
        }
    }

    suspend fun processUserInput(text: String) {
        Timber.d("processUserInput called with: '$text'")
        _state.value = VoicePipelineState.Processing
        _partialText.value = text
        _lastResponse.value = ""
        resetWatchdog()

        // Fast path: match common intents and execute directly, skipping LLM round-trip.
        // Target <200ms from final STT to spoken confirmation (Priority 1).
        val fastMatch = fastPathRouter?.match(text)
        if (fastMatch != null) {
            latencyRecorder.startSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
            val handled = tryHandleFastPath(text, fastMatch)
            val ms = latencyRecorder.endSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
            if (handled) {
                Timber.d("Fast-path completed in ${ms}ms")
                return
            }
        }

        // Play thinking sound if enabled
        playThinkingSound()

        // Start filler phrases job (speaks initial ack + wait phrases if processing takes long)
        val fillerJob = startFillerPhrasesJob()

        try {
            // Pass the user input so Auto policy can escalate heavy tasks
            // (long input, vision, code review) to a remote provider when
            // one is registered.
            val provider = router.resolveProvider(userInput = text)
            Timber.d("Provider resolved: ${provider.id}")
            if (currentSession == null) {
                Timber.d("Creating new session...")
                currentSession = provider.startSession()
                Timber.d("Session created")
            }

            val userMessage = AssistantMessage.User(content = text)
            conversationHistory.add(userMessage)
            trimConversationHistory()
            persistUserMessage(text)

            // Filter to intent-relevant tools when the user input matches any
            // bucket; otherwise pass the whole list. Local LLMs benefit most.
            val tools = com.opendash.app.tool.ToolFilter.filterByIntent(
                allTools = toolExecutor.availableTools(),
                userInput = text
            )
            var toolRounds = 0

            _state.value = VoicePipelineState.Thinking

            while (toolRounds < MAX_TOOL_ROUNDS) {
                latencyRecorder.startSpan(LatencyRecorder.Span.LLM_ROUND_TRIP, key = "round_$toolRounds")
                val response = provider.send(currentSession!!, conversationHistory, tools)
                val llmMs = latencyRecorder.endSpan(
                    LatencyRecorder.Span.LLM_ROUND_TRIP, key = "round_$toolRounds"
                )
                Timber.d("LLM round $toolRounds completed in ${llmMs}ms")

                when (response) {
                    is AssistantMessage.Assistant -> {
                        conversationHistory.add(response)

                        if (response.toolCalls.isNotEmpty()) {
                            for (toolCallReq in response.toolCalls) {
                                val args = parseToolArguments(toolCallReq.arguments)
                                val toolCall = ToolCall(
                                    id = toolCallReq.id,
                                    name = toolCallReq.name,
                                    arguments = args
                                )
                                latencyRecorder.startSpan(
                                    LatencyRecorder.Span.TOOL_EXECUTION,
                                    key = "tool_${toolCallReq.id}"
                                )
                                val toolResult = toolExecutor.execute(toolCall)
                                latencyRecorder.endSpan(
                                    LatencyRecorder.Span.TOOL_EXECUTION,
                                    key = "tool_${toolCallReq.id}"
                                )
                                Timber.d(
                                    "Agent round $toolRounds: called=${toolCallReq.name}, " +
                                        "result=${toolResult.success}"
                                )
                                val resultMessage = AssistantMessage.ToolCallResult(
                                    callId = toolCallReq.id,
                                    result = if (toolResult.success) toolResult.data else (toolResult.error ?: "Error"),
                                    isError = !toolResult.success
                                )
                                conversationHistory.add(resultMessage)
                            }
                            toolRounds++
                            continue
                        }

                        // Cancel any in-progress filler phrases before speaking the response
                        fillerJob.cancel()
                        tts.stop()

                        _lastResponse.value = response.content
                        persistAssistantMessage(response.content)
                        Timber.d("Speaking response: ${response.content.take(50)}...")

                        // Check if TTS is enabled
                        val ttsEnabled = preferences.observe(PreferenceKeys.TTS_ENABLED).first() ?: true
                        if (ttsEnabled) {
                            _state.value = VoicePipelineState.Speaking
                            // Barge-in: re-arm wake word BEFORE we start speaking
                            // so the user can interrupt TTS playback by calling
                            // the wake word (Alexa/Nest parity). When detected,
                            // VoiceService → voicePipeline.startListening() will
                            // hit the `state is Speaking` branch and tts.stop().
                            resumeWakeWordForBargeInIfEnabled()
                            try {
                                latencyRecorder.startSpan(LatencyRecorder.Span.TTS_PREPARATION)
                                tts.speak(response.content)
                                latencyRecorder.endSpan(LatencyRecorder.Span.TTS_PREPARATION)
                                Timber.d("TTS completed")
                            } catch (e: Exception) {
                                Timber.e(e, "TTS failed")
                            }
                        }

                        // Continuous conversation mode (see finishTurnAndMaybeContinue)
                        finishTurnAndMaybeContinue()
                        return
                    }
                    else -> {
                        abandonAudioFocus()
                        resumeWakeWord()
                        _state.value = VoicePipelineState.Idle
                        return
                    }
                }
            }

            // Agent round cap hit: the LLM kept asking for tools past the
            // safety limit. Speak a graceful fallback so the user isn't left
            // in silence. See MAX_TOOL_ROUNDS.
            Timber.w("Agent hit MAX_TOOL_ROUNDS ($MAX_TOOL_ROUNDS); emitting fallback reply")
            fillerJob.cancel()
            tts.stop()
            val ttsLangForFallback = preferences.observe(PreferenceKeys.TTS_LANGUAGE).first()
            val fallback = AgentFallback.roundCapMessage(ttsLangForFallback)
            _lastResponse.value = fallback
            persistAssistantMessage(fallback)
            val ttsEnabled = preferences.observe(PreferenceKeys.TTS_ENABLED).first() ?: true
            if (ttsEnabled) {
                _state.value = VoicePipelineState.Speaking
                resumeWakeWordForBargeInIfEnabled()
                try {
                    tts.speak(fallback)
                } catch (e: Exception) {
                    Timber.e(e, "TTS failed for round-cap fallback")
                }
            }
            abandonAudioFocus()
            resumeWakeWord()
            _state.value = VoicePipelineState.Idle
        } catch (e: Exception) {
            Timber.e(e, "Voice pipeline error")
            fillerJob.cancel()
            val recovery = errorClassifier.classify(e.message, e, kind = currentProviderKind())
            _lastResponse.value = recovery.userSpokenMessage
            abandonAudioFocus()
            _state.value = VoicePipelineState.Error(recovery.userSpokenMessage)
            delay(if (recovery.canRetry) 3000 else 5000)
            resumeWakeWord()
            _state.value = VoicePipelineState.Idle
        } finally {
            fillerJob.cancel()
        }
    }

    /**
     * Starts a background job that speaks filler/wait phrases while the LLM is processing.
     * Cancelled automatically when the response is ready or an error occurs.
     * Reference: OpenClaw Assistant OpenClawSession.scheduleInitialFillerPhrase / playWaitPhrase
     */
    private fun startFillerPhrasesJob(): Job {
        return scope.launch {
            val enabled = preferences.observe(PreferenceKeys.FILLER_PHRASES_ENABLED).first() ?: false
            if (!enabled) return@launch

            val lang = preferences.observe(PreferenceKeys.TTS_LANGUAGE).first()

            // Initial acknowledgment after 1.5s
            delay(1500)
            try {
                tts.speak(com.opendash.app.voice.FillerPhrases.initialPhrase(lang))
            } catch (_: Exception) { /* cancelled or other */ }

            // Subsequent wait phrases every 6-8s
            while (isActive) {
                delay(6000 + (0..2000).random().toLong())
                try {
                    tts.speak(com.opendash.app.voice.FillerPhrases.waitPhrase(lang))
                } catch (_: Exception) { /* cancelled */ }
            }
        }
    }

    fun showError(message: String) {
        _lastResponse.value = message
        _state.value = VoicePipelineState.Error(message)
        scope.launch {
            delay(4000)
            _state.value = VoicePipelineState.Idle
        }
    }

    fun interruptAndListen() {
        tts.stop()
        scope.launch { startListening() }
    }

    fun stopSpeaking() {
        tts.stop()
        abandonAudioFocus()
        resumeWakeWord()
        _state.value = VoicePipelineState.Idle
    }

    fun clearHistory() {
        conversationHistory.clear()
        currentSession = null
        val sid = persistedSessionId
        persistedSessionId = null
        if (sid != null && sessionDao != null) {
            scope.launch {
                try {
                    sessionDao.deleteById(sid)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to delete persisted session")
                }
            }
        }
    }

    // --- Audio Focus ---

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    // --- Thinking Sound ---

    private suspend fun playThinkingSound() {
        val enabled = preferences.observe(PreferenceKeys.THINKING_SOUND).first() ?: true
        if (!enabled) return

        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Timber.w("Could not play thinking sound: ${e.message}")
        }
    }

    private fun playListeningBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
        } catch (e: Exception) {
            Timber.w("Could not play listening beep: ${e.message}")
        }
    }

    private fun playErrorBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 150)
        } catch (e: Exception) {
            Timber.w("Could not play error beep: ${e.message}")
        }
    }

    // --- Turn End ---

    /**
     * End the current turn. When the user has enabled the "Continuous
     * Conversation" preference, re-arm the mic after a short delay so the
     * user can keep talking without having to say the wake word or tap the
     * mic button again. Otherwise fall back to the classic Idle-with-
     * wake-word behaviour.
     *
     * Called from both the LLM path (after speaking the assistant reply)
     * and the fast-path success branch (after speaking the fast-path
     * confirmation) so users who turn the preference on see the same
     * behaviour regardless of which route handled the turn. Error paths
     * deliberately do NOT call this — falling back to Idle lets the user
     * regroup rather than trapping them in a retry loop.
     */
    private suspend fun finishTurnAndMaybeContinue() {
        val continuousMode = preferences.observe(PreferenceKeys.CONTINUOUS_MODE).first() ?: false
        if (continuousMode) {
            Timber.d("Continuous mode: restarting listening after delay")
            delay(CONTINUOUS_MODE_DELAY_MS)
            startListening()
        } else {
            abandonAudioFocus()
            resumeWakeWord()
            _state.value = VoicePipelineState.Idle
        }
    }

    // --- Wake Word Resume ---

    private fun resumeWakeWord() {
        VoiceService.resumeHotword(context)
    }

    /**
     * Re-arm the wake-word detector during TTS playback so users can say
     * "dash" to interrupt a reply (barge-in). VoiceService handles the
     * actual detector lifecycle: ACTION_RESUME_HOTWORD flips isSessionActive
     * back to false and re-starts the Vosk detector on a 500ms delay. When
     * the detector fires mid-speech, its callback calls
     * voicePipeline.startListening(), which catches `state is Speaking` and
     * stops TTS before entering the STT turn — identical to the existing
     * mic-tap barge-in path.
     *
     * No-op when BARGE_IN_ENABLED=false so users who dislike interruptions
     * keep the legacy "wake word off during TTS" behaviour.
     */
    private suspend fun resumeWakeWordForBargeInIfEnabled() {
        val bargeInEnabled = preferences.observe(PreferenceKeys.BARGE_IN_ENABLED).first() ?: true
        if (!bargeInEnabled) return
        VoiceService.resumeHotword(context)
    }

    // --- Preference Application ---

    private suspend fun applySttPreferences() {
        // Pipeline is injected with DelegatingSttProvider; reach through to the
        // Android backend for prefs that only apply to it (language / silence).
        val androidStt = when (val s = this.stt) {
            is AndroidSttProvider -> s
            is com.opendash.app.voice.stt.DelegatingSttProvider ->
                s.androidDelegate() as? AndroidSttProvider
            else -> null
        } ?: return
        val sttLang = preferences.observe(PreferenceKeys.STT_LANGUAGE).first()?.takeIf { it.isNotBlank() }
        val silence = preferences.observe(PreferenceKeys.SILENCE_TIMEOUT_MS).first() ?: 1500L
        val minSpeech = preferences.observe(PreferenceKeys.MIN_SPEECH_MS).first() ?: 400L
        androidStt.language = sttLang
        androidStt.silenceTimeoutMs = silence
        androidStt.minSpeechMs = minSpeech
        Timber.d("STT prefs applied: lang=$sttLang, silence=${silence}ms, minSpeech=${minSpeech}ms")
    }

    private suspend fun applyTtsLanguagePreference() {
        val lang = preferences.observe(PreferenceKeys.TTS_LANGUAGE).first()?.takeIf { it.isNotBlank() } ?: return
        // Only TtsManager exposes a setLanguage entry point; other provider
        // implementations pull language from prefs at speak time.
        (this.tts as? com.opendash.app.voice.tts.TtsManager)?.setLanguage(lang)
    }

    // --- Fast Path ---

    /**
     * Execute a fast-path command directly. Persists a fake assistant message
     * so conversation history still shows what happened.
     * Returns true if the fast-path handled the turn end-to-end (state → Idle).
     */
    /**
     * State carried across turns so a "はい / いいえ" reply after
     * "○○さんに電話してよろしいですか?" can resolve to the right call
     * without relying on the on-device LLM to chain tool-use correctly.
     * Null unless a PhoneCallMatcher pending_confirmation was the most
     * recent fast-path hit AND the 30-second window is still open.
     */
    private data class PendingCall(
        val displayName: String,
        val phone: String,
        val expiresAt: Long,
    )

    @Volatile
    private var pendingCall: PendingCall? = null

    private fun currentPendingCall(): PendingCall? {
        val pc = pendingCall ?: return null
        if (pc.expiresAt < System.currentTimeMillis()) {
            pendingCall = null
            return null
        }
        return pc
    }

    private suspend fun placeConfirmedCall(pending: PendingCall) {
        val result = toolExecutor.execute(
            ToolCall(
                id = "confirmed_call_${System.currentTimeMillis()}",
                name = "make_call",
                arguments = mapOf(
                    "contact_name" to pending.displayName,
                    "phone_number" to pending.phone,
                    "confirmed" to true,
                ),
            )
        )
        val spoken = if (result.success) {
            "${pending.displayName}さんに発信します"
        } else {
            "電話を発信できませんでした"
        }
        _lastResponse.value = spoken
        val assistantMessage = AssistantMessage.Assistant(content = spoken)
        conversationHistory.add(assistantMessage)
        trimConversationHistory()
        persistAssistantMessage(spoken)
        _state.value = VoicePipelineState.Speaking
        resumeWakeWordForBargeInIfEnabled()
        speakResponse(spoken)
        onResponseComplete()
    }

    private suspend fun speakResponse(text: String) {
        runCatching { tts.speak(text) }
            .onFailure { Timber.w(it, "TTS failed for confirmation speech") }
    }

    private suspend fun onResponseComplete() {
        _state.value = VoicePipelineState.Idle
        resumeWakeWord()
    }

    /**
     * Auto-restart listening after a pending-call prompt finishes
     * speaking, regardless of the `continuous_mode` preference. The
     * user can't say "hey speaker, yes" — they expect the device to
     * already be waiting for their yes/no answer. Narrow override for
     * this one flow so we don't trample the global preference.
     */
    private fun armListeningForConfirmation() {
        scope.launch {
            delay(CONTINUOUS_MODE_DELAY_MS)
            startListening()
        }
    }

    /**
     * Tiny JSON string-field extractor for the make_call pending-
     * confirmation payload. Flat single-object JSON only — good enough
     * for `{"resolved_name":"橋本","resolved_phone":"090..."}` and keeps
     * us out of the full kotlinx.serialization ceremony for a single
     * callsite.
     */
    private fun extractJsonStringField(data: String, field: String): String? {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
        val match = pattern.find(data) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private suspend fun tryHandleFastPath(
        userText: String,
        match: com.opendash.app.voice.fastpath.FastPathMatch
    ): Boolean {
        // Pending-call confirmation / cancellation are pure VoicePipeline
        // concerns — no ToolExecutor route exists for them, they just
        // pivot on the pendingCall slot we saved when make_call returned
        // pending_confirmation.
        if (match.toolName == "__confirm_pending_call__") {
            val pending = currentPendingCall()
            pendingCall = null
            if (pending == null) {
                return false // nothing pending → fall through to LLM path
            }
            Timber.d("Fast-path: confirming pending call to ${pending.displayName}")
            placeConfirmedCall(pending)
            return true
        }
        if (match.toolName == "__cancel_pending_call__") {
            val pending = currentPendingCall()
            pendingCall = null
            if (pending == null) return false
            Timber.d("Fast-path: cancelling pending call to ${pending.displayName}")
            val spoken = "キャンセルしました"
            _lastResponse.value = spoken
            conversationHistory.add(AssistantMessage.Assistant(content = spoken))
            trimConversationHistory()
            persistAssistantMessage(spoken)
            _state.value = VoicePipelineState.Speaking
            resumeWakeWordForBargeInIfEnabled()
            speakResponse(spoken)
            onResponseComplete()
            return true
        }
        // Any other fast-path utterance invalidates a stale pending call
        // (user changed topic) — drop it so "はい" two turns later
        // doesn't accidentally dial the old target.
        if (match.toolName != "make_call") {
            pendingCall = null
        }

        return try {
            Timber.d("Fast-path matched: ${match.toolName ?: "(speak-only)"}")
            // Speak-only matches (e.g. "help") skip tool execution entirely.
            val result = match.toolName?.let { toolName ->
                toolExecutor.execute(
                    ToolCall(
                        id = "fast_${System.currentTimeMillis()}",
                        name = toolName,
                        arguments = match.arguments
                    )
                )
            }
            // Special-case make_call: when the tool returns a
            // pending_confirmation payload we store the resolved phone
            // and speak the "○○さんに電話してよろしいですか?" prompt
            // verbatim. The next turn's "はい" goes through
            // ConfirmCallMatcher (handled above) to place the call.
            if (match.toolName == "make_call" && result?.success == true) {
                val data = result.data
                if (data.contains("\"pending_confirmation\":true")) {
                    val askUser = extractJsonStringField(data, "ask_user")
                        ?: "この方に電話をかけてよろしいですか？"
                    val resolvedName = extractJsonStringField(data, "resolved_name").orEmpty()
                    val resolvedPhone = extractJsonStringField(data, "resolved_phone").orEmpty()
                    if (resolvedPhone.isNotBlank()) {
                        pendingCall = PendingCall(
                            displayName = resolvedName.ifBlank { "この方" },
                            phone = resolvedPhone,
                            expiresAt = System.currentTimeMillis() + PENDING_CALL_TIMEOUT_MS,
                        )
                        Timber.d("Fast-path: stashed pending call to $resolvedName ($resolvedPhone)")
                    }
                    // Screen shows the resolved number so the user can
                    // visually double-check STT didn't mis-hear the name.
                    // TTS reads only the spoken prompt so it doesn't
                    // recite a ten-digit number out loud.
                    val displayText = if (resolvedPhone.isNotBlank()) {
                        val displayName = resolvedName.ifBlank { "この方" }
                        "$displayName さん（$resolvedPhone）に電話をかけてよろしいですか？"
                    } else {
                        askUser
                    }
                    val userMessage = AssistantMessage.User(content = userText)
                    conversationHistory.add(userMessage)
                    conversationHistory.add(AssistantMessage.Assistant(content = displayText))
                    trimConversationHistory()
                    persistUserMessage(userText)
                    persistAssistantMessage(displayText)
                    _lastResponse.value = displayText
                    _state.value = VoicePipelineState.Speaking
                    resumeWakeWordForBargeInIfEnabled()
                    speakResponse(askUser)
                    _state.value = VoicePipelineState.Idle
                    armListeningForConfirmation()
                    return true
                }
            }
            val spoken = when {
                match.spokenConfirmation != null -> match.spokenConfirmation
                result == null -> "Done."
                result.success -> {
                    // Info tools (weather / forecast / web_search / news) have
                    // meaningful JSON payloads we want spoken back to the user.
                    // Every other success case stays on "Done." to preserve
                    // the previous short-confirmation feel.
                    val rawTtsLang = preferences.observe(PreferenceKeys.TTS_LANGUAGE).first()
                    // Fall back to the device locale when the user hasn't
                    // explicitly picked a TTS language. On a Japanese tablet
                    // this resolves to "ja-JP" so both the LLM polish *and*
                    // the regex formatter speak Japanese instead of
                    // defaulting to English.
                    val ttsLang = resolveTtsLanguageTag(rawTtsLang)
                    val toolName = match.toolName ?: ""
                    // Try LLM polishing first for info tools — gives natural
                    // language replies (translates Open-Meteo conditions,
                    // avoids "Tokyo 18 Clear 65% 12 km/h"). Falls back to the
                    // regex formatter on timeout / error / non-info tool.
                    //
                    // Polish every info tool, including web_search. Previously
                    // web_search was excluded on the theory that Gemma 270m-2B
                    // would refuse to speak SERP snippets, but skipping polish
                    // left us reading raw DuckDuckGo titles (which are often
                    // ad copy — "LINE レンジャーに関してオンラインで相談…")
                    // verbatim. On Gemma 4 E2B the polish consistently produces
                    // a useful summary; ErrorClassifier catches any residual
                    // refusal and falls back to the regex formatter below.
                    val shouldPolish = toolName in FastPathLlmPolisher.SUPPORTED_TOOLS
                    val polished = if (shouldPolish) {
                        try {
                            val provider = router.resolveProvider(userInput = userText)
                            fastPathLlmPolisher.polish(
                                provider = provider,
                                toolName = toolName,
                                userText = userText,
                                resultData = result.data,
                                ttsLanguageTag = ttsLang
                            )
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to resolve provider for LLM polish")
                            null
                        }
                    } else null
                    polished ?: FastPathResultFormatter.format(
                        toolName = toolName,
                        data = result.data,
                        ttsLanguageTag = ttsLang
                    )
                }
                else -> {
                    // Route the tool's raw error through ErrorClassifier so
                    // multi-room categories (no shared secret, HMAC mismatch,
                    // replay-window reject, no peers) surface their targeted
                    // user-friendly copy instead of the generic fallback.
                    // `classify` is already keyword-matched on the broadcaster
                    // error strings — see ErrorClassifier for the single source
                    // of truth.
                    errorClassifier.classify(
                        result.error,
                        kind = currentProviderKind()
                    ).userSpokenMessage
                }
            }
            _lastResponse.value = spoken

            // Persist minimal history so follow-up can reference it
            val userMessage = AssistantMessage.User(content = userText)
            val assistantMessage = AssistantMessage.Assistant(content = spoken)
            conversationHistory.add(userMessage)
            conversationHistory.add(assistantMessage)
            trimConversationHistory()
            persistUserMessage(userText)

            val ttsEnabled = preferences.observe(PreferenceKeys.TTS_ENABLED).first() ?: true
            if (ttsEnabled) {
                _state.value = VoicePipelineState.Speaking
                // Barge-in on fast-path too: re-arm wake word before speaking
                // so short fast-path confirmations ("Timer set for 5 minutes.")
                // are interruptible just like LLM replies.
                resumeWakeWordForBargeInIfEnabled()
                try {
                    tts.speak(spoken)
                } catch (e: Exception) {
                    Timber.w(e, "TTS failed on fast-path")
                }
            }

            // Share the continuous-conversation logic with the LLM path so
            // fast-path turns (weather / timer / web search / …) also honour
            // the "Continuous Conversation" toggle. Before this was a bug —
            // users who enabled it still saw fast-path turns end in Idle.
            finishTurnAndMaybeContinue()
            true
        } catch (e: Exception) {
            Timber.w(e, "Fast-path execution failed, falling back to LLM")
            false
        }
    }

    // --- Conversation History ---

    private fun trimConversationHistory() {
        val maxMessages = 50
        if (conversationHistory.size > maxMessages) {
            val systemMessages = conversationHistory.filterIsInstance<AssistantMessage.System>()
            val recentMessages = conversationHistory.takeLast(maxMessages - systemMessages.size)
            conversationHistory.clear()
            conversationHistory.addAll(systemMessages + recentMessages)
        }
    }

    // --- Watchdog ---

    private fun startWatchdog() {
        cancelWatchdog()
        watchdogJob = scope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (isActive) {
                tts.stop()
                stt.stopListening()
                abandonAudioFocus()
                _state.value = VoicePipelineState.Idle
            }
        }
    }

    private fun resetWatchdog() { startWatchdog() }
    private fun cancelWatchdog() { watchdogJob?.cancel(); watchdogJob = null }

    // --- Tool Arguments ---

    @Suppress("UNCHECKED_CAST")
    private fun parseToolArguments(json: String): Map<String, Any?> {
        return try {
            moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun destroy() {
        toneGenerator?.release()
        toneGenerator = null
        abandonAudioFocus()
    }
}
