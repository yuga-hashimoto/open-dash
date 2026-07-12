package com.opendash.app.voice.vad.silero

import com.opendash.app.voice.stt.whisper.VadEngine

/**
 * Neural [VadEngine] backed by Silero's ONNX model, an opt-in
 * higher-accuracy alternative to [com.opendash.app.voice.stt.whisper.AmplitudeVad]'s
 * RMS-threshold heuristic — meant to distinguish real speech from
 * background noise/music far better than a raw amplitude cutoff can,
 * per P16.2 ("Silero-ONNX VAD... follow-up for whisper-quality voice
 * activity detection").
 *
 * Buffers arbitrary-length [feed] calls into Silero's required
 * 512-sample (32ms @ 16kHz) windows, prepends the trailing 64 samples
 * of context from the previous window (Silero's own streaming
 * contract — see [SileroVadSession]'s KDoc), and carries the model's
 * recurrent `state` between calls. Endpoint semantics (minSpeechMs /
 * silenceTrailMs) intentionally mirror [com.opendash.app.voice.stt.whisper.AmplitudeVad]'s
 * so swapping engines doesn't change capture-timing behavior — only
 * the per-chunk speech/silence classification itself changes, from an
 * RMS threshold to the model's probability output.
 *
 * NOT YET VALIDATED against real audio or a real device — see
 * docs/roadmap.md's P16.2 entry. The buffering/context-carry logic
 * was ported from Silero's own published `OnnxWrapper.__call__`
 * reference implementation and is unit-tested against a fake
 * [SileroVadSession], but only real-device testing can confirm actual
 * detection accuracy.
 */
class SileroVadEngine(
    private val session: SileroVadSession,
    /** Silero's own docs recommend ~0.5 as the default speech-probability threshold. */
    private val speechThreshold: Float = 0.5f,
    private val minSpeechMs: Int = 300,
    private val silenceTrailMs: Int = 800,
    private val sampleRate: Int = 16_000
) : VadEngine {

    private var context = FloatArray(SileroVadSession.CONTEXT_SAMPLES)
    private var state = FloatArray(SileroVadSession.STATE_SIZE)
    private val pending = ArrayDeque<Float>()
    private var speechMs = 0
    private var silenceMs = 0
    private var endpointDetected = false

    override fun feed(chunk: FloatArray, len: Int): VadEngine.Decision {
        if (endpointDetected) return VadEngine.Decision.EndpointDetected
        if (len <= 0) return VadEngine.Decision.Listening

        for (i in 0 until len) pending.addLast(chunk[i])

        while (pending.size >= SileroVadSession.CHUNK_SAMPLES && !endpointDetected) {
            val window = FloatArray(SileroVadSession.CHUNK_SAMPLES) { pending.removeFirst() }
            processWindow(window)
        }

        return if (endpointDetected) VadEngine.Decision.EndpointDetected else VadEngine.Decision.Listening
    }

    override fun reset() {
        context = FloatArray(SileroVadSession.CONTEXT_SAMPLES)
        state = FloatArray(SileroVadSession.STATE_SIZE)
        pending.clear()
        speechMs = 0
        silenceMs = 0
        endpointDetected = false
    }

    private fun processWindow(window: FloatArray) {
        val input = context + window
        val result = session.infer(input, state)
        state = result.nextState
        context = input.copyOfRange(input.size - SileroVadSession.CONTEXT_SAMPLES, input.size)

        val windowMs = (window.size * 1000L / sampleRate).toInt()
        if (result.speechProbability >= speechThreshold) {
            speechMs += windowMs
            silenceMs = 0
        } else if (speechMs >= minSpeechMs) {
            silenceMs += windowMs
        }

        if (speechMs >= minSpeechMs && silenceMs >= silenceTrailMs) {
            endpointDetected = true
        }
    }
}
