package com.opendash.app.voice.stt.whisper

/**
 * Endpoint-detection contract shared by [AmplitudeVad] (RMS-threshold,
 * always available) and [com.opendash.app.voice.vad.silero.SileroVadEngine]
 * (neural, opt-in — see that package for why it isn't the default yet).
 * [AudioRecordPcmSource] depends on this interface rather than a concrete
 * VAD so either can be plugged in via `vadFactory`.
 */
interface VadEngine {
    enum class Decision { Listening, EndpointDetected }

    /** Feed a chunk of float samples; [len] is the valid prefix length (matches AudioRecord.read()'s return value). */
    fun feed(chunk: FloatArray, len: Int): Decision

    /** Reset internal state so the next capture session starts fresh. */
    fun reset()
}
