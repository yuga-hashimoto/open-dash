package com.opendash.app.voice.stt.whisper

import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.SttResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File

/**
 * P14.1 on-device STT via whisper.cpp.
 *
 * Replaces [com.opendash.app.voice.stt.OfflineSttStub] on the
 * `WHISPER_OFFLINE` route in `DelegatingSttProvider`. Contract:
 *
 *  - `startListening()` returns a cold Flow that emits exactly one
 *    [SttResult.Final] on success, or one [SttResult.Error] if any gate
 *    is closed (no native lib, no model file, no mic permission).
 *  - Partial results are NOT emitted today — whisper.cpp runs
 *    `whisper_full` in batch mode over the captured buffer, so streaming
 *    is a follow-up.
 *  - `stopListening()` flips the PCM source off; any capture in progress
 *    returns whatever it has so far and `transcribe()` runs on the
 *    partial buffer. A future streaming impl will swap this for a true
 *    cancellation path.
 *
 * On stock builds today [availabilityCheck] returns false because
 * `externalNativeBuild` is commented out in `app/build.gradle.kts` — so
 * this provider degrades to an Error result. Injectable check lets
 * tests run the downstream load/capture/transcribe paths without
 * needing the JNI.
 */
class WhisperSttProvider(
    private val bridge: WhisperCppBridge,
    private val pcmSource: WhisperPcmSource,
    private val modelPathProvider: () -> File,
    private val language: String = "auto",
    private val translate: Boolean = false,
    private val maxSeconds: Int = MAX_CAPTURE_SECONDS_DEFAULT,
    private val availabilityCheck: () -> Boolean = { WhisperCppBridge.isAvailable() }
) : SpeechToText {

    private val _listening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _listening.asStateFlow()

    override fun startListening(): Flow<SttResult> = flow {
        if (!availabilityCheck()) {
            emit(SttResult.Error("Whisper native library is not available on this build"))
            return@flow
        }

        val modelFile = modelPathProvider()
        if (!modelFile.exists() || modelFile.length() == 0L) {
            emit(SttResult.Error("Whisper model not downloaded yet. Download a model in Settings first."))
            return@flow
        }

        if (!bridge.isModelLoaded()) {
            val ok = bridge.loadModel(modelFile.absolutePath)
            if (!ok) {
                emit(SttResult.Error("Whisper failed to load model at ${modelFile.name}"))
                return@flow
            }
        }

        _listening.value = true
        try {
            val samples = try {
                pcmSource.capture(SAMPLE_RATE * maxSeconds)
            } catch (e: SecurityException) {
                emit(SttResult.Error("Microphone permission is required for Whisper STT"))
                return@flow
            } catch (e: Exception) {
                Timber.w(e, "Whisper PCM capture failed")
                emit(SttResult.Error("Could not capture audio: ${e.message.orEmpty()}"))
                return@flow
            }

            if (samples.isEmpty()) {
                emit(SttResult.Final(text = "", confidence = 0f))
                return@flow
            }

            val text = try {
                bridge.transcribe(samples, language = language, translate = translate).trim()
            } catch (e: Exception) {
                Timber.w(e, "whisper_full threw")
                emit(SttResult.Error("Whisper transcription failed: ${e.message.orEmpty()}"))
                return@flow
            }

            emit(SttResult.Final(text = text, confidence = 1f))
        } finally {
            _listening.value = false
        }
    }

    override fun stopListening() {
        pcmSource.stop()
        _listening.value = false
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_CAPTURE_SECONDS_DEFAULT = 15
    }
}
