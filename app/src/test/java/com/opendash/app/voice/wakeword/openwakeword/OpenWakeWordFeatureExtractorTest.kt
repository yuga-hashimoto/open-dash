package com.opendash.app.voice.wakeword.openwakeword

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OpenWakeWordFeatureExtractorTest {

    private class FakeSessions(
        override val classifierWindowSize: Int = 4,
        private val melspecFramesPerCall: Int = 8,
        private val score: Float = 0.9f
    ) : OpenWakeWordSessions {
        val melspecContextSizes = mutableListOf<Int>()
        var embeddingCalls = 0
        var classifyCalls = 0
        var lastEmbeddingWindowSize = -1
        var lastClassifyWindowSize = -1

        override fun melspectrogram(samples: FloatArray): List<FloatArray> {
            melspecContextSizes.add(samples.size)
            return List(melspecFramesPerCall) { FloatArray(OpenWakeWordSessions.MELSPEC_BINS) { 1f } }
        }

        override fun embedding(melspecWindow: List<FloatArray>): FloatArray {
            embeddingCalls++
            lastEmbeddingWindowSize = melspecWindow.size
            return FloatArray(OpenWakeWordSessions.EMBEDDING_DIM) { 0.5f }
        }

        override fun classify(embeddingWindow: List<FloatArray>): Float {
            classifyCalls++
            lastClassifyWindowSize = embeddingWindow.size
            return score
        }
    }

    private fun chunk(): FloatArray = FloatArray(OpenWakeWordSessions.FRAME_SAMPLES) { 0f }

    @Test
    fun `processChunk rejects a chunk that is not exactly FRAME_SAMPLES long`() {
        val extractor = OpenWakeWordFeatureExtractor(FakeSessions())
        try {
            extractor.processChunk(FloatArray(100))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("1280")
        }
    }

    @Test
    fun `returns null while the mel-spectrogram buffer is still warming up`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8)
        val extractor = OpenWakeWordFeatureExtractor(sessions)

        // 8 frames/call: after 9 calls, buffer has 72 frames (< 76) — still null.
        var lastResult: Float? = 0f
        repeat(9) { lastResult = extractor.processChunk(chunk()) }

        assertThat(lastResult).isNull()
        assertThat(sessions.embeddingCalls).isEqualTo(0)
    }

    @Test
    fun `starts producing embeddings once 76 mel-spectrogram frames have accumulated`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8, classifierWindowSize = 100)
        val extractor = OpenWakeWordFeatureExtractor(sessions)

        // 10th call: 80 frames total (>= 76) — embedding should now run exactly once.
        repeat(10) { extractor.processChunk(chunk()) }

        assertThat(sessions.embeddingCalls).isEqualTo(1)
        assertThat(sessions.lastEmbeddingWindowSize).isEqualTo(OpenWakeWordSessions.EMBEDDING_WINDOW_FRAMES)
    }

    @Test
    fun `classifier runs once enough embeddings accumulate, using the model's own window size`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8, classifierWindowSize = 4)
        val extractor = OpenWakeWordFeatureExtractor(sessions)

        // Embeddings start at call 10; classifier needs 4 embeddings -> fires at call 13.
        var result: Float? = null
        repeat(13) { result = extractor.processChunk(chunk()) }

        assertThat(sessions.classifyCalls).isEqualTo(1)
        assertThat(sessions.lastClassifyWindowSize).isEqualTo(4)
        // First real prediction is inside the warm-up window, so it's forced to 0.
        assertThat(result).isEqualTo(0f)
    }

    @Test
    fun `first five real predictions are zeroed regardless of the model score`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8, classifierWindowSize = 4, score = 0.95f)
        val extractor = OpenWakeWordFeatureExtractor(sessions)

        val results = mutableListOf<Float?>()
        // Calls 10-13 warm up embeddings/classifier; classify starts firing at call 13.
        repeat(20) { results.add(extractor.processChunk(chunk())) }

        val classifierResults = results.drop(12) // calls 13..20, the ones where classify actually ran
        assertThat(classifierResults.take(5)).containsExactly(0f, 0f, 0f, 0f, 0f)
        assertThat(classifierResults.drop(5).all { it == 0.95f }).isTrue()
    }

    @Test
    fun `melspectrogram context grows toward the lookback cap then stays fixed`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8)
        val extractor = OpenWakeWordFeatureExtractor(sessions)

        // First call: only 1280 samples available (no history yet).
        extractor.processChunk(chunk())
        assertThat(sessions.melspecContextSizes[0]).isEqualTo(1280)

        // Second call: 2560 raw samples available, but context caps at 1280 + 480 = 1760.
        extractor.processChunk(chunk())
        assertThat(sessions.melspecContextSizes[1]).isEqualTo(1760)

        // Third call: still capped at 1760.
        extractor.processChunk(chunk())
        assertThat(sessions.melspecContextSizes[2]).isEqualTo(1760)
    }

    @Test
    fun `reset clears buffers so warm-up starts over`() {
        val sessions = FakeSessions(melspecFramesPerCall = 8, classifierWindowSize = 4)
        val extractor = OpenWakeWordFeatureExtractor(sessions)
        repeat(13) { extractor.processChunk(chunk()) }
        assertThat(sessions.classifyCalls).isEqualTo(1)

        extractor.reset()

        val afterReset = extractor.processChunk(chunk())
        assertThat(afterReset).isNull()
        assertThat(sessions.melspecContextSizes.last()).isEqualTo(1280) // no history after reset
    }
}
