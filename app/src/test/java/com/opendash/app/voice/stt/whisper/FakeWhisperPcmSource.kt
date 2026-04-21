package com.opendash.app.voice.stt.whisper

/**
 * Test-only [WhisperPcmSource] that returns a preloaded float array
 * regardless of the requested maxSamples. The caller can inspect
 * [stopCallCount] to assert whether the provider released capture.
 */
class FakeWhisperPcmSource(
    private val samples: FloatArray,
    private val throwOnCapture: Exception? = null
) : WhisperPcmSource {

    var stopCallCount = 0
        private set

    override suspend fun capture(maxSamples: Int): FloatArray {
        throwOnCapture?.let { throw it }
        return if (samples.size <= maxSamples) samples else samples.copyOf(maxSamples)
    }

    override fun stop() {
        stopCallCount++
    }
}
