package com.opendash.app.voice.stt.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.opendash.app.voice.AudioEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [WhisperPcmSource] backed by [AudioRecord] at whisper's native
 * 16 kHz sample rate. Converts int16 PCM to float32 in [-1.0, 1.0] inline
 * so the JNI layer can pass the pointer straight through.
 *
 * The caller is responsible for holding RECORD_AUDIO — we check in capture()
 * just so the failure surfaces as a clear SecurityException rather than a
 * cryptic AudioRecord init error.
 *
 * Amplitude VAD: each captured chunk is fed to [AmplitudeVad]. Once the
 * VAD declares the user has stopped talking, capture returns early with
 * the collected prefix — no more fixed-15-second waits. Disable by
 * passing `vadEnabled = false` (the wake-word keep-alive path still does
 * timed captures).
 */
class AudioRecordPcmSource(
    private val context: Context,
    private val vadEnabled: Boolean = true,
    private val vadFactory: () -> AmplitudeVad = { AmplitudeVad(sampleRate = SAMPLE_RATE) }
) : WhisperPcmSource {

    private val running = AtomicBoolean(false)
    @Volatile
    private var audioRecord: AudioRecord? = null

    override suspend fun capture(maxSamples: Int): FloatArray = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize returned $minBuffer")
        }

        // ~50 ms of audio per read. Shorter than the original 0.5 s chunk
        // so the VAD has tighter endpoint resolution — silence trailing
        // the user's last word is detected in ~100 ms instead of ~1 s.
        val chunkSamples = ((SAMPLE_RATE / 20).coerceAtLeast(minBuffer / 4)).coerceAtLeast(512)
        val chunk = ShortArray(chunkSamples)
        val collected = FloatArray(maxSamples)
        var offset = 0
        val vad = if (vadEnabled) vadFactory() else null

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBuffer
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }

        audioRecord = record
        running.set(true)
        record.startRecording()
        val echoCanceler = AudioEffects.applyAcousticEchoCanceler(record.audioSessionId)
        val noiseSuppressor = AudioEffects.applyNoiseSuppressor(record.audioSessionId)
        try {
            while (running.get() && offset < maxSamples) {
                val remaining = (maxSamples - offset).coerceAtMost(chunk.size)
                val read = record.read(chunk, 0, remaining)
                if (read <= 0) break
                val floatChunk = FloatArray(read)
                for (i in 0 until read) {
                    // int16 range → float in [-1, 1]. Division by 32768f matches
                    // whisper.cpp's expected input scaling.
                    val f = chunk[i] / 32768f
                    collected[offset + i] = f
                    floatChunk[i] = f
                }
                offset += read

                if (vad != null) {
                    when (vad.feed(floatChunk, read)) {
                        AmplitudeVad.Decision.EndpointDetected -> {
                            Timber.d("Whisper VAD endpoint detected at ${offset * 1000 / SAMPLE_RATE}ms")
                            break
                        }
                        AmplitudeVad.Decision.Listening -> { /* keep going */ }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioRecord read failed")
        } finally {
            running.set(false)
            AudioEffects.release(echoCanceler, noiseSuppressor)
            runCatching { record.stop() }
            runCatching { record.release() }
            audioRecord = null
        }

        if (offset == maxSamples) collected else collected.copyOf(offset)
    }

    override fun stop() {
        running.set(false)
        // release() from the capture path will see running=false on next loop.
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
