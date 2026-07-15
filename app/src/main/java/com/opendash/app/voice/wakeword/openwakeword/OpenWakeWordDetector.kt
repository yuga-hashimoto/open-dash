package com.opendash.app.voice.wakeword.openwakeword

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.opendash.app.voice.AudioEffects
import com.opendash.app.voice.wakeword.WakeWordDetector
import com.opendash.app.voice.wakeword.WakeWordHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * openWakeWord-based wake word detector — an opt-in alternative to the
 * default [com.opendash.app.voice.wakeword.VoskWakeWordDetector] for
 * one of the small set of English preset keywords openWakeWord ships
 * pre-trained models for (currently just "hey jarvis" — see
 * [OpenWakeWordModelCatalog]'s KDoc for why no custom-keyword or
 * Japanese support exists here, unlike Vosk).
 *
 * Structure deliberately mirrors [com.opendash.app.voice.wakeword.VoskWakeWordDetector]
 * (start/stop/pause/resume, retry backoff, [AudioEffects] AEC/NS on the
 * capture stream) so it's a drop-in alternative once
 * [com.opendash.app.service.VoiceService] is updated to construct
 * either engine based on a Settings preference — that wiring is
 * intentionally NOT done yet; see docs/roadmap.md's P21 entry for why.
 *
 * NOT YET VALIDATED against real audio or a real device — see
 * [OpenWakeWordFeatureExtractor]'s KDoc for what has and hasn't been
 * cross-checked against the upstream Python reference implementation.
 */
class OpenWakeWordDetector(
    private val threshold: Float,
    private val modelDir: File
) : WakeWordDetector {

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _health = MutableStateFlow<WakeWordHealth>(
        WakeWordHealth.Unavailable("not started")
    )
    override val health: StateFlow<WakeWordHealth> = _health.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var sessions: OrtOpenWakeWordSessions? = null
    private var onDetectedCallback: (() -> Unit)? = null
    @Volatile
    private var isPaused = false

    override fun start(onDetected: () -> Unit) {
        if (_isListening.value) return
        onDetectedCallback = onDetected
        isPaused = false

        listeningJob = scope.launch {
            var retryCount = 0
            var lastError: String? = null
            while (retryCount < MAX_RETRY_ATTEMPTS) {
                try {
                    initializeSessions()
                    startAudioLoop()
                    break
                } catch (e: Exception) {
                    retryCount++
                    lastError = e.message ?: e.javaClass.simpleName
                    Timber.e(e, "openWakeWord detection failed (attempt $retryCount)")
                    val backoff = (RETRY_BACKOFF_BASE_MS * retryCount).coerceAtMost(RETRY_BACKOFF_MAX_MS)
                    delay(backoff)
                }
            }
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                Timber.e("openWakeWord detection failed after $MAX_RETRY_ATTEMPTS attempts")
                _isListening.value = false
                _health.value = WakeWordHealth.Failed(
                    lastError ?: "openWakeWord failed after $MAX_RETRY_ATTEMPTS attempts"
                )
            }
        }
    }

    override fun stop() {
        isPaused = true
        _isListening.value = false
        _health.value = WakeWordHealth.Paused
        listeningJob?.cancel()
        listeningJob = null
        releaseAudio()
    }

    /** Pause to release the microphone for STT — same contract as VoskWakeWordDetector.pause(). */
    fun pause() {
        isPaused = true
        _isListening.value = false
        _health.value = WakeWordHealth.Paused
        listeningJob?.cancel()
        listeningJob = null
        releaseAudio()
    }

    /** Resume after an STT session ends — same contract as VoskWakeWordDetector.resume(). */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        onDetectedCallback?.let { start(it) }
    }

    private fun initializeSessions() {
        if (sessions != null) return
        val melspec = File(modelDir, OpenWakeWordModelCatalog.MELSPECTROGRAM.filename)
        val embedding = File(modelDir, OpenWakeWordModelCatalog.EMBEDDING.filename)
        val classifier = File(modelDir, OpenWakeWordModelCatalog.HEY_JARVIS.filename)
        if (!melspec.exists() || !embedding.exists() || !classifier.exists()) {
            throw IllegalStateException("openWakeWord models not downloaded at ${modelDir.absolutePath}")
        }
        sessions = OrtOpenWakeWordSessions.create(melspec, embedding, classifier)
            ?: throw IllegalStateException("Failed to load openWakeWord ONNX sessions")
    }

    @Suppress("MissingPermission")
    private fun startAudioLoop() {
        val ort = sessions ?: return
        val extractor = OpenWakeWordFeatureExtractor(ort)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Invalid audio buffer size: $bufferSize")
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            (bufferSize * 2).coerceAtLeast(OpenWakeWordSessions.FRAME_SAMPLES * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        audioRecord = record

        record.startRecording()
        echoCanceler = AudioEffects.applyAcousticEchoCanceler(record.audioSessionId)
        noiseSuppressor = AudioEffects.applyNoiseSuppressor(record.audioSessionId)
        _isListening.value = true
        _health.value = WakeWordHealth.Listening
        Timber.d("openWakeWord listening (hey jarvis)")

        val chunk = ShortArray(OpenWakeWordSessions.FRAME_SAMPLES)
        try {
            while (_isListening.value && !isPaused) {
                val ar = audioRecord ?: break
                var offset = 0
                while (offset < chunk.size) {
                    val read = try {
                        ar.read(chunk, offset, chunk.size - offset)
                    } catch (e: IllegalStateException) {
                        Timber.d("openWakeWord: AudioRecord read threw (likely released) — exiting loop")
                        if (!isPaused) {
                            _health.value = WakeWordHealth.Failed("AudioRecord released")
                        }
                        return
                    }
                    if (read < 0) {
                        Timber.d("openWakeWord: AudioRecord read returned error $read — exiting loop")
                        if (!isPaused) {
                            _health.value = WakeWordHealth.Failed("AudioRecord error $read")
                        }
                        return
                    }
                    if (read == 0) break
                    offset += read
                }
                if (offset != chunk.size) continue // paused/stopped mid-read

                val floatSamples = FloatArray(chunk.size) { i -> chunk[i].toFloat() }
                val score = try {
                    extractor.processChunk(floatSamples)
                } catch (e: Exception) {
                    Timber.w(e, "openWakeWord inference failed on a chunk")
                    null
                }
                if (score != null && score >= threshold) {
                    Timber.d("openWakeWord detected 'hey jarvis' (score=$score)")
                    pause()
                    onDetectedCallback?.invoke()
                    return
                }
            }
        } finally {
            releaseAudio()
            _isListening.value = false
            if (_health.value is WakeWordHealth.Listening) {
                _health.value = if (isPaused) WakeWordHealth.Paused
                else WakeWordHealth.Failed("openWakeWord audio loop stopped unexpectedly")
            }
        }
    }

    private fun releaseAudio() {
        AudioEffects.release(echoCanceler, noiseSuppressor)
        echoCanceler = null
        noiseSuppressor = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Timber.w(e, "Error releasing AudioRecord")
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_BACKOFF_BASE_MS = 1000L
        private const val RETRY_BACKOFF_MAX_MS = 10000L
        /** openWakeWord's own docs recommend 0.5 as the default threshold for pre-trained models. */
        const val DEFAULT_THRESHOLD = 0.5f
    }
}
