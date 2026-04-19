package com.opendash.app.voice.tts

import android.content.Context
import android.media.MediaPlayer
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.tts.voicevox.VoiceVoxCharacters
import com.opendash.app.voice.tts.voicevox.VoiceVoxModelManager
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Embedded (on-device) VOICEVOX TTS provider for the `full` product flavor.
 *
 * Uses the bundled [voicevoxcore-android-0.16.4.aar] + libvoicevox_onnxruntime.so
 * to synthesize WAV audio locally, with no network dependency once the
 * OpenJTalk dict and the active VVM are on disk.
 *
 * Initialization is lazy: the native synthesizer is only created on the first
 * `speak()` call when the dict + active VVM are both present. If either is
 * missing, the call silently returns — UI flows are responsible for prompting
 * the user to download via [VoiceVoxModelManager] before invoking speech.
 *
 * Stolen from openclaw-assistant's `VoiceVoxProvider`; reshaped to match
 * open-dash's simpler [TextToSpeech] contract (no `TTSState` progress flow).
 */
class EmbeddedVoiceVoxTtsProvider(
    private val context: Context,
    private val preferences: AppPreferences,
    httpClient: OkHttpClient,
) : TextToSpeech {

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    val modelManager: VoiceVoxModelManager = VoiceVoxModelManager(context, httpClient)

    // Native resources — all guarded by [engineMutex] to avoid concurrent JNI calls.
    private val engineMutex = Mutex()
    private var synthesizer: Synthesizer? = null
    private var openJtalk: OpenJtalk? = null
    private var currentModel: VoiceModelFile? = null
    private var currentVvmFile: String? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var initialized = false

    override suspend fun speak(text: String) {
        if (text.isBlank()) return

        val termsAccepted = preferences.observe(PreferenceKeys.VOICEVOX_TERMS_ACCEPTED).first() ?: false
        if (!termsAccepted) {
            Timber.w("VOICEVOX embedded: terms not accepted, skipping")
            return
        }

        val styleId = preferences.observe(PreferenceKeys.VOICEVOX_STYLE_ID).first()
            ?: VoiceVoxCharacters.DEFAULT_STYLE_ID
        val speechRate = preferences.observe(PreferenceKeys.TTS_SPEECH_RATE).first() ?: 1.0f

        if (!modelManager.isDictionaryReady()) {
            Timber.w("VOICEVOX embedded: dictionary missing, download required")
            return
        }

        val vvmName = VoiceVoxCharacters.getVvmFileForStyle(styleId)
        if (!modelManager.isVvmReady(vvmName)) {
            Timber.w("VOICEVOX embedded: VVM $vvmName missing, download required")
            return
        }

        val cleanText = TtsUtils.stripMarkdownForSpeech(text)

        val wavFile: File = withContext(Dispatchers.IO) {
            engineMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock null
                if (!loadVoiceModelLocked(styleId, vvmName)) return@withLock null
                val synth = synthesizer ?: return@withLock null

                try {
                    val query = synth.createAudioQuery(cleanText, styleId)
                    query.speedScale = speechRate.toDouble()
                    val wavData = synth.synthesis(query, styleId).perform()

                    val tmp = File.createTempFile("voicevox_", ".wav", context.cacheDir)
                    FileOutputStream(tmp).use { it.write(wavData) }
                    tmp
                } catch (e: Exception) {
                    Timber.e(e, "VOICEVOX embedded synthesis failed")
                    null
                }
            }
        } ?: return

        try {
            playFile(wavFile)
        } finally {
            wavFile.delete()
        }
    }

    override fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _isSpeaking.value = false
    }

    /**
     * Release all native resources. Safe to call multiple times. After this
     * returns, the next [speak] will re-initialize.
     */
    suspend fun shutdown() {
        stop()
        engineMutex.withLock {
            try {
                currentModel?.close()
            } catch (_: Exception) {
            }
            currentModel = null
            currentVvmFile = null
            openJtalk = null
            synthesizer = null
            initialized = false
        }
    }

    // ── Private: must hold engineMutex ────────────────────────────────────

    private fun ensureInitializedLocked(): Boolean {
        if (initialized) return true
        return try {
            Timber.d("VOICEVOX embedded: loading native runtime")
            val ort = Onnxruntime.loadOnce().perform()
            val dictPath = modelManager.dictionaryPath()
            openJtalk = OpenJtalk(dictPath)
            synthesizer = Synthesizer.builder(ort, openJtalk).build()
            initialized = true
            Timber.d("VOICEVOX embedded: initialized (dict=$dictPath)")
            true
        } catch (e: Throwable) {
            Timber.e(e, "VOICEVOX embedded: initialization failed")
            openJtalk = null
            synthesizer = null
            initialized = false
            false
        }
    }

    private fun loadVoiceModelLocked(styleId: Int, vvmName: String): Boolean {
        if (currentVvmFile == vvmName && currentModel != null) return true
        val file = modelManager.vvmFile(vvmName)
        if (!file.exists()) {
            Timber.e("VVM missing on disk: ${file.absolutePath}")
            return false
        }
        return try {
            currentModel?.close()
            val model = VoiceModelFile(file.absolutePath)
            synthesizer?.loadVoiceModel(model)
            currentModel = model
            currentVvmFile = vvmName
            Timber.d("VOICEVOX embedded: loaded $vvmName.vvm for style $styleId")
            true
        } catch (e: Throwable) {
            Timber.e(e, "VOICEVOX embedded: loadVoiceModel failed")
            currentModel = null
            currentVvmFile = null
            false
        }
    }

    private suspend fun playFile(file: File) {
        suspendCancellableCoroutine<Unit> { cont ->
            try {
                mediaPlayer?.release()
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { start() }
                    setOnCompletionListener {
                        _isSpeaking.value = false
                        if (cont.isActive) cont.resume(Unit)
                    }
                    setOnErrorListener { _, what, extra ->
                        Timber.e("VOICEVOX embedded MediaPlayer error: what=$what extra=$extra")
                        _isSpeaking.value = false
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    prepare()
                }
                mediaPlayer = mp
                _isSpeaking.value = true

                cont.invokeOnCancellation {
                    try { mp.stop() } catch (_: Exception) {}
                    try { mp.release() } catch (_: Exception) {}
                    _isSpeaking.value = false
                }
            } catch (e: Exception) {
                Timber.e(e, "VOICEVOX embedded playback failed")
                _isSpeaking.value = false
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
