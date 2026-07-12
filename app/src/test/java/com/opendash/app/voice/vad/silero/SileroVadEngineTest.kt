package com.opendash.app.voice.vad.silero

import com.google.common.truth.Truth.assertThat
import com.opendash.app.voice.stt.whisper.VadEngine
import org.junit.jupiter.api.Test

class SileroVadEngineTest {

    /** Returns a fixed probability per call, in order; repeats the last value once exhausted. */
    private class ScriptedSession(private val probabilities: List<Float>) : SileroVadSession {
        var callCount = 0
        val inputSizes = mutableListOf<Int>()
        val stateSizes = mutableListOf<Int>()

        override fun infer(inputSamples: FloatArray, state: FloatArray): SileroVadSession.Result {
            inputSizes.add(inputSamples.size)
            stateSizes.add(state.size)
            val prob = probabilities.getOrElse(callCount) { probabilities.last() }
            callCount++
            // Echo a distinguishable "next state" so we can verify it's threaded through.
            val nextState = FloatArray(SileroVadSession.STATE_SIZE) { callCount.toFloat() }
            return SileroVadSession.Result(prob, nextState)
        }
    }

    private fun silentChunk() = FloatArray(SileroVadSession.CHUNK_SAMPLES) { 0f }

    @Test
    fun `each 512-sample window is prefixed with 64 samples of context`() {
        val session = ScriptedSession(listOf(0f))
        val engine = SileroVadEngine(session)

        engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES)

        assertThat(session.inputSizes).containsExactly(SileroVadSession.CHUNK_SAMPLES + SileroVadSession.CONTEXT_SAMPLES)
        assertThat(session.stateSizes).containsExactly(SileroVadSession.STATE_SIZE)
    }

    @Test
    fun `feed buffers partial chunks until a full window is available`() {
        val session = ScriptedSession(listOf(0f))
        val engine = SileroVadEngine(session)

        // Feed in small pieces that don't individually reach CHUNK_SAMPLES.
        val piece = FloatArray(200) { 0f }
        engine.feed(piece, 200)
        assertThat(session.callCount).isEqualTo(0)
        engine.feed(piece, 200)
        assertThat(session.callCount).isEqualTo(0)
        engine.feed(piece, 200)
        // 600 samples fed total -> one full 512-window processed, 88 left buffered.
        assertThat(session.callCount).isEqualTo(1)
    }

    @Test
    fun `does not declare endpoint while probability stays below threshold`() {
        val session = ScriptedSession(listOf(0.1f))
        val engine = SileroVadEngine(session, minSpeechMs = 100, silenceTrailMs = 100)

        var last: VadEngine.Decision = VadEngine.Decision.Listening
        repeat(20) { last = engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES) }

        assertThat(last).isEqualTo(VadEngine.Decision.Listening)
    }

    @Test
    fun `declares endpoint after enough speech followed by enough silence`() {
        // 512 samples @ 16kHz = 32ms/window. minSpeechMs=100 needs ~4 windows of
        // speech; silenceTrailMs=100 needs ~4 more windows of silence after.
        val session = ScriptedSession(
            List(5) { 0.9f } + List(5) { 0.05f }
        )
        val engine = SileroVadEngine(session, minSpeechMs = 100, silenceTrailMs = 100)

        var decision: VadEngine.Decision = VadEngine.Decision.Listening
        repeat(10) { decision = engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES) }

        assertThat(decision).isEqualTo(VadEngine.Decision.EndpointDetected)
    }

    @Test
    fun `stops calling the session once an endpoint has already been detected`() {
        val session = ScriptedSession(List(5) { 0.9f } + List(5) { 0.05f })
        val engine = SileroVadEngine(session, minSpeechMs = 100, silenceTrailMs = 100)
        repeat(10) { engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES) }
        val callsAtDetection = session.callCount

        engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES)
        engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES)

        assertThat(session.callCount).isEqualTo(callsAtDetection)
    }

    @Test
    fun `reset clears buffered samples state and endpoint latch`() {
        val session = ScriptedSession(List(5) { 0.9f } + List(5) { 0.05f })
        val engine = SileroVadEngine(session, minSpeechMs = 100, silenceTrailMs = 100)
        repeat(10) { engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES) }
        assertThat(engine.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES)).isEqualTo(VadEngine.Decision.EndpointDetected)

        engine.reset()

        val session2 = ScriptedSession(listOf(0.9f))
        val engine2 = SileroVadEngine(session2, minSpeechMs = 100, silenceTrailMs = 100)
        assertThat(engine2.feed(silentChunk(), SileroVadSession.CHUNK_SAMPLES)).isEqualTo(VadEngine.Decision.Listening)
    }

    @Test
    fun `zero or negative length returns Listening without buffering`() {
        val session = ScriptedSession(listOf(0.9f))
        val engine = SileroVadEngine(session)

        assertThat(engine.feed(FloatArray(10), 0)).isEqualTo(VadEngine.Decision.Listening)
        assertThat(engine.feed(FloatArray(10), -1)).isEqualTo(VadEngine.Decision.Listening)
        assertThat(session.callCount).isEqualTo(0)
    }
}
