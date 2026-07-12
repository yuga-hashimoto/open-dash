package com.opendash.app.voice.stt.whisper

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sin

class AmplitudeVadTest {

    private fun silentChunk(samples: Int): FloatArray = FloatArray(samples) { 0f }

    /**
     * Synthetic "speech" — a 440 Hz sine wave at amplitude 0.3, well
     * above the default 0.01 RMS threshold. Real speech is noisier but
     * comparable in amplitude.
     */
    private fun speechChunk(samples: Int, sampleRate: Int = 16_000): FloatArray =
        FloatArray(samples) { i ->
            (0.3 * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toFloat()
        }

    @Test
    fun `silence only never declares endpoint`() {
        val vad = AmplitudeVad()
        repeat(100) {
            val chunk = silentChunk(160) // 10 ms at 16 kHz
            assertThat(vad.feed(chunk, chunk.size))
                .isEqualTo(VadEngine.Decision.Listening)
        }
    }

    @Test
    fun `speech alone keeps listening`() {
        val vad = AmplitudeVad(minSpeechMs = 300, silenceTrailMs = 500)
        repeat(100) {
            val chunk = speechChunk(160)
            assertThat(vad.feed(chunk, chunk.size))
                .isEqualTo(VadEngine.Decision.Listening)
        }
    }

    @Test
    fun `speech then silence past trail threshold triggers endpoint`() {
        val vad = AmplitudeVad(minSpeechMs = 300, silenceTrailMs = 500)

        // 400 ms of speech — crosses minSpeech.
        repeat(40) {
            vad.feed(speechChunk(160), 160)
        }
        // 600 ms of silence — crosses silenceTrail.
        val decisions = (1..60).map { vad.feed(silentChunk(160), 160) }

        assertThat(decisions).contains(VadEngine.Decision.EndpointDetected)
        // The first endpoint should fire around the 50th silent chunk
        // (500 ms) — assertion is loose to allow for off-by-one framing.
        val idx = decisions.indexOfFirst { it == VadEngine.Decision.EndpointDetected }
        assertThat(idx).isIn(48..52)
    }

    @Test
    fun `silence before minSpeechMs does not count toward trail`() {
        val vad = AmplitudeVad(minSpeechMs = 300, silenceTrailMs = 400)

        // 1 s of silence first — pre-speech, shouldn't contribute.
        repeat(100) { vad.feed(silentChunk(160), 160) }
        // Now 350 ms of speech — crosses minSpeech.
        repeat(35) { vad.feed(speechChunk(160), 160) }
        // 500 ms of silence — should now trigger endpoint because
        // speechMs has accumulated past minSpeechMs.
        val decisions = (1..50).map { vad.feed(silentChunk(160), 160) }

        assertThat(decisions).contains(VadEngine.Decision.EndpointDetected)
    }

    @Test
    fun `brief silence within speech does not trigger endpoint`() {
        val vad = AmplitudeVad(minSpeechMs = 300, silenceTrailMs = 800)

        // Speech, brief pause (shorter than trail), more speech — should
        // stay in Listening throughout.
        repeat(40) { vad.feed(speechChunk(160), 160) } // 400 ms speech
        repeat(30) {
            val d = vad.feed(silentChunk(160), 160) // 300 ms silence
            assertThat(d).isEqualTo(VadEngine.Decision.Listening)
        }
        repeat(30) {
            val d = vad.feed(speechChunk(160), 160) // 300 ms more speech
            assertThat(d).isEqualTo(VadEngine.Decision.Listening)
        }
    }

    @Test
    fun `len zero or negative treated as listening with no state change`() {
        val vad = AmplitudeVad()
        assertThat(vad.feed(FloatArray(10), 0)).isEqualTo(VadEngine.Decision.Listening)
        assertThat(vad.feed(FloatArray(10), -5)).isEqualTo(VadEngine.Decision.Listening)
    }

    @Test
    fun `reset clears accumulated state`() {
        val vad = AmplitudeVad(minSpeechMs = 300, silenceTrailMs = 400)
        repeat(40) { vad.feed(speechChunk(160), 160) }
        repeat(20) { vad.feed(silentChunk(160), 160) }
        vad.reset()

        // Fresh state — 20 ms of speech + 500 ms silence should NOT
        // trigger endpoint yet (speech < minSpeech).
        vad.feed(speechChunk(160), 160) // 10 ms
        vad.feed(speechChunk(160), 160) // 20 ms total
        val decisions = (1..50).map { vad.feed(silentChunk(160), 160) }
        assertThat(decisions).doesNotContain(VadEngine.Decision.EndpointDetected)
    }
}
