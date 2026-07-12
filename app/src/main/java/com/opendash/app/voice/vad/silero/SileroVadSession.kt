package com.opendash.app.voice.vad.silero

/**
 * Wraps the single Silero VAD ONNX Runtime session, abstracted so
 * [SileroVadEngine] is unit-testable without a real ONNX Runtime session
 * — same reasoning as [com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordSessions].
 *
 * Exact contract verified against the real model file during
 * implementation (input `(1, 576)` = 64-sample carried context +
 * 512-sample new chunk; `state` shape `(2, 1, 128)`; `sr` = 16000 int64;
 * outputs a speech-probability scalar plus the next `state`).
 */
interface SileroVadSession {
    data class Result(val speechProbability: Float, val nextState: FloatArray)

    /**
     * [inputSamples] must be exactly [CHUNK_SAMPLES] + [CONTEXT_SAMPLES]
     * long (context-prefixed chunk). [state] must be [STATE_SIZE] long
     * (flattened `(2, 1, 128)`).
     */
    fun infer(inputSamples: FloatArray, state: FloatArray): Result

    companion object {
        const val CHUNK_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        const val STATE_SIZE = 2 * 1 * 128
        const val SAMPLE_RATE = 16_000L
    }
}
