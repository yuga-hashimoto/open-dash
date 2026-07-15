package com.opendash.app.voice.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.opendash.app.R
import com.opendash.app.assistant.provider.embedded.ModelDownloader
import com.opendash.app.voice.stt.whisper.WhisperCppBridge
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import com.opendash.app.voice.tts.piper.PiperCppBridge
import com.opendash.app.voice.tts.piper.PiperVoiceCatalog
import com.opendash.app.voice.tts.piper.PiperVoiceDownloader

/**
 * Diagnostic checks for voice-related subsystems.
 * Reference: OpenClaw Assistant VoiceDiagnostics.
 *
 * Results are consumed by the Settings UI "Voice Health" section to give users
 * actionable feedback when voice features don't work.
 */
object VoiceDiagnostics {

    enum class Severity { OK, WARNING, ERROR }

    data class DiagnosticItem(
        val id: String,
        val title: String,
        val message: String,
        val severity: Severity,
        val actionLabel: String? = null,
        val actionIntent: Intent? = null
    )

    fun run(
        context: Context,
        offlineInputsProvider: () -> OfflineVoiceProfile.Inputs = {
            probeOfflineInputs(context)
        },
        measurementRecorder: VoiceMeasurementRecorder? = null,
    ): List<DiagnosticItem> {
        val items = mutableListOf<DiagnosticItem>()

        items += checkRecordAudioPermission(context)
        items += checkMicrophonePrivacy(context)
        items += checkSpeechRecognition(context)
        items += checkTtsEngine(context)
        items += checkOfflineVoiceProfile(context, offlineInputsProvider())
        measurementRecorder?.let { items += measurementSessionItem(it) }

        return items
    }

    /**
     * Summary card for the in-memory measurement session. Counts and device
     * metadata only — never transcripts or secrets.
     */
    fun measurementSessionItem(recorder: VoiceMeasurementRecorder): DiagnosticItem {
        val snap = recorder.snapshot()
        val latencyBits = snap.latency.entries
            .sortedBy { it.key }
            .joinToString("; ") { (name, s) ->
                "$name n=${s.count} avg=${s.averageMs}ms p95=${s.p95Ms}ms"
            }
            .ifBlank { "none" }
        val message = buildString {
            append("samples=${snap.sampleCount}")
            append(" wakes=${snap.wakeCount}")
            append(" falseWakes=${snap.falseWakeCount}")
            append(" stt=${snap.sttFinalCount}/${snap.sttErrorCount}")
            append(" tts=${snap.ttsStopCount}")
            append(" batterySamples=${snap.batterySamples.size}")
            append(" thermalSamples=${snap.thermalSamples.size}")
            append(" latency=[$latencyBits]")
            append(" — instrumentation only; not physical validation")
        }
        return DiagnosticItem(
            id = "voice_measurement",
            title = "Voice measurement session",
            message = message,
            severity = Severity.OK,
        )
    }

    /**
     * Probe local STT / LLM / system TTS / neural TTS readiness without
     * initializing asynchronous TextToSpeech engines. Gates use bridge
     * availability and on-disk model/voice presence only — never the selected
     * preference alone.
     */
    fun probeOfflineInputs(context: Context): OfflineVoiceProfile.Inputs {
        val whisperNative = WhisperCppBridge.isAvailable()
        val whisperDownloader = WhisperModelDownloader(context)
        val whisperModelReady = WhisperModelCatalog.all.any { whisperDownloader.isDownloaded(it) }
        val localSttReady = whisperNative && whisperModelReady
        val localSttReason = when {
            localSttReady -> context.getString(R.string.voice_health_offline_stt_ready)
            !whisperNative && !whisperModelReady ->
                context.getString(R.string.voice_health_offline_stt_missing_both)
            !whisperNative -> context.getString(R.string.voice_health_offline_stt_missing_native)
            else -> context.getString(R.string.voice_health_offline_stt_missing_model)
        }

        val llmReady = ModelDownloader(context).isModelDownloaded()
        val localLlmReason = if (llmReady) {
            context.getString(R.string.voice_health_offline_llm_ready)
        } else {
            context.getString(R.string.voice_health_offline_llm_missing)
        }

        val systemTtsReady = isSystemTtsEnginePresent(context)
        val systemTtsReason = if (systemTtsReady) {
            context.getString(R.string.voice_health_offline_system_tts_ready)
        } else {
            context.getString(R.string.voice_health_offline_system_tts_missing)
        }

        val piperNative = PiperCppBridge.isAvailable()
        val piperDownloader = PiperVoiceDownloader(context)
        val piperVoiceReady = PiperVoiceCatalog.all.any { piperDownloader.isDownloaded(it) }
        val neuralReady = piperNative && piperVoiceReady
        val neuralReason = when {
            neuralReady -> context.getString(R.string.voice_health_offline_neural_tts_ready)
            !piperNative && !piperVoiceReady ->
                context.getString(R.string.voice_health_offline_neural_tts_missing_both)
            !piperNative -> context.getString(R.string.voice_health_offline_neural_tts_missing_native)
            else -> context.getString(R.string.voice_health_offline_neural_tts_missing_voice)
        }

        return OfflineVoiceProfile.Inputs(
            localStt = OfflineVoiceProfile.ComponentStatus(
                ready = localSttReady,
                label = context.getString(R.string.voice_health_offline_component_stt),
                reason = localSttReason
            ),
            localLlm = OfflineVoiceProfile.ComponentStatus(
                ready = llmReady,
                label = context.getString(R.string.voice_health_offline_component_llm),
                reason = localLlmReason
            ),
            systemTts = OfflineVoiceProfile.ComponentStatus(
                ready = systemTtsReady,
                label = context.getString(R.string.voice_health_offline_component_system_tts),
                reason = systemTtsReason
            ),
            neuralTts = OfflineVoiceProfile.ComponentStatus(
                ready = neuralReady,
                label = context.getString(R.string.voice_health_offline_component_neural_tts),
                reason = neuralReason
            )
        )
    }

    private fun checkOfflineVoiceProfile(
        context: Context,
        inputs: OfflineVoiceProfile.Inputs
    ): DiagnosticItem {
        val profile = OfflineVoiceProfile.evaluate(inputs)
        val missing = profile.components.filterNot { it.ready }
        val detail = if (missing.isEmpty()) {
            profile.components.joinToString("; ") { "${it.label}: ${it.reason}" }
        } else {
            missing.joinToString("; ") { "${it.label}: ${it.reason}" }
        }

        val (message, severity) = when (profile.supportedMode) {
            OfflineVoiceProfile.SupportedMode.FullyOffline ->
                context.getString(R.string.voice_health_offline_mode_fully_offline, detail) to Severity.OK
            OfflineVoiceProfile.SupportedMode.LocalSTTAndLLMSystemTts ->
                context.getString(R.string.voice_health_offline_mode_system_tts, detail) to Severity.WARNING
            OfflineVoiceProfile.SupportedMode.NotReady ->
                context.getString(R.string.voice_health_offline_mode_not_ready, detail) to Severity.ERROR
        }

        return DiagnosticItem(
            id = "offline_voice_profile",
            title = context.getString(R.string.voice_health_offline_profile_title),
            message = message,
            severity = severity
        )
    }

    private fun checkRecordAudioPermission(context: Context): DiagnosticItem {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            DiagnosticItem(
                id = "record_audio",
                title = "Microphone Permission",
                message = "Granted",
                severity = Severity.OK
            )
        } else {
            DiagnosticItem(
                id = "record_audio",
                title = "Microphone Permission",
                message = "Not granted — voice input won't work",
                severity = Severity.ERROR,
                actionLabel = "Open App Settings",
                actionIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
            )
        }
    }

    private fun checkMicrophonePrivacy(context: Context): DiagnosticItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return DiagnosticItem(
                id = "mic_privacy",
                title = "Microphone Hardware",
                message = "OK",
                severity = Severity.OK
            )
        }
        val spm = context.getSystemService(SensorPrivacyManager::class.java)
        val supports = spm?.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE) == true
        val audioManager = context.getSystemService(AudioManager::class.java)
        val muted = audioManager?.isMicrophoneMute == true
        return when {
            supports && muted -> DiagnosticItem(
                id = "mic_privacy",
                title = "Microphone Hardware",
                message = "Microphone is muted by system privacy toggle",
                severity = Severity.ERROR,
                actionLabel = "Open Privacy Settings",
                actionIntent = Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS)
            )
            else -> DiagnosticItem(
                id = "mic_privacy",
                title = "Microphone Hardware",
                message = "OK",
                severity = Severity.OK
            )
        }
    }

    private fun checkSpeechRecognition(context: Context): DiagnosticItem {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        return if (available) {
            DiagnosticItem(
                id = "stt_availability",
                title = "Speech Recognition",
                message = "Available on this device",
                severity = Severity.OK
            )
        } else {
            DiagnosticItem(
                id = "stt_availability",
                title = "Speech Recognition",
                message = "No speech recognition service installed. Install Google app or another recognition provider.",
                severity = Severity.ERROR,
                actionLabel = "Find in Play Store",
                actionIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://details?id=com.google.android.googlequicksearchbox")
                }
            )
        }
    }

    /**
     * Try to discover whether at least one TTS engine is installed.
     * Deeper voice-data checks would require initializing TextToSpeech which is async;
     * here we use the TTS_SERVICE intent query as a cheap first-line check.
     */
    private fun checkTtsEngine(context: Context): DiagnosticItem {
        val services = queryTtsServices(context)
        return if (services.isNotEmpty()) {
            DiagnosticItem(
                id = "tts_engine",
                title = "Text-to-Speech Engine",
                message = "${services.size} engine(s) detected",
                severity = Severity.OK
            )
        } else {
            DiagnosticItem(
                id = "tts_engine",
                title = "Text-to-Speech Engine",
                message = "No TTS engine installed. Install Google Text-to-Speech.",
                severity = Severity.ERROR,
                actionLabel = "Install Google TTS",
                actionIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://details?id=com.google.android.tts")
                }
            )
        }
    }

    private fun isSystemTtsEnginePresent(context: Context): Boolean =
        queryTtsServices(context).isNotEmpty()

    private fun queryTtsServices(context: Context) =
        context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0
        )
}
