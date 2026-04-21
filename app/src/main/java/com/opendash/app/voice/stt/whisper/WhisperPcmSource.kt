package com.opendash.app.voice.stt.whisper

/**
 * Abstraction over microphone PCM capture so [WhisperSttProvider] stays
 * JVM-unit-testable. The production binding is
 * [AudioRecordPcmSource] — it opens an `AudioRecord`, reads int16 samples,
 * converts to float32 in the [-1.0, 1.0] range, and hands chunks back to
 * the provider until [stop] is called.
 *
 * Tests inject a pre-baked [FakeWhisperPcmSource] that emits a canned
 * buffer so the provider's availability / model-missing / transcribe
 * paths are exercised without an actual microphone.
 */
interface WhisperPcmSource {

    /**
     * Capture up to [maxSamples] of 16 kHz mono float32 PCM. Implementations
     * may return early if [stop] is called or the audio source is
     * exhausted; the returned array length must equal the number of
     * valid samples.
     *
     * Throws [SecurityException] if RECORD_AUDIO is not granted.
     */
    suspend fun capture(maxSamples: Int): FloatArray

    /**
     * Signal the in-progress [capture] to return whatever it's collected
     * so far. No-op if capture isn't running.
     */
    fun stop()
}
