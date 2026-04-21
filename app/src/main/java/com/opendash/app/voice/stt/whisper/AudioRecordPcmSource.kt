package com.opendash.app.voice.stt.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
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
 */
class AudioRecordPcmSource(
    private val context: Context
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

        // ~0.5 s of audio per read. We collect until stop() or maxSamples.
        val chunkSamples = (SAMPLE_RATE / 2).coerceAtLeast(minBuffer / 2)
        val chunk = ShortArray(chunkSamples)
        val collected = FloatArray(maxSamples)
        var offset = 0

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
        try {
            while (running.get() && offset < maxSamples) {
                val remaining = (maxSamples - offset).coerceAtMost(chunk.size)
                val read = record.read(chunk, 0, remaining)
                if (read <= 0) break
                for (i in 0 until read) {
                    // int16 range → float in [-1, 1]. Division by 32768f matches
                    // whisper.cpp's expected input scaling.
                    collected[offset + i] = chunk[i] / 32768f
                }
                offset += read
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioRecord read failed")
        } finally {
            running.set(false)
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
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
