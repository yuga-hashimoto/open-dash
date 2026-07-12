package com.opendash.app.voice.metrics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LatencyRecorderTest {

    @Test
    fun `startSpan and endSpan record duration`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })

        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now = 400_000_000L // 400ms later (in nanoseconds)
        val durationMs = recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        assertThat(durationMs).isEqualTo(400L)
    }

    @Test
    fun `endSpan without start returns null`() {
        val recorder = LatencyRecorder()
        assertThat(recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)).isNull()
    }

    @Test
    fun `summarize computes average p50 p95 max`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })

        // Record 10 samples of WAKE_TO_LISTENING: 100, 200, ..., 1000 ms
        for (i in 1..10) {
            recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
            now += (i * 100L) * 1_000_000L
            recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        }

        val summary = recorder.summarize().first { it.event == "WAKE_TO_LISTENING" }
        assertThat(summary.count).isEqualTo(10)
        assertThat(summary.averageMs).isEqualTo(550L) // (100+...+1000)/10
        assertThat(summary.maxMs).isEqualTo(1000L)
        assertThat(summary.p50Ms).isAtLeast(500L)
        assertThat(summary.p95Ms).isAtLeast(900L)
    }

    @Test
    fun `reset clears all data`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        recorder.startSpan(LatencyRecorder.Span.STT_DURATION)
        now = 1_000_000L
        recorder.endSpan(LatencyRecorder.Span.STT_DURATION)
        recorder.reset()

        assertThat(recorder.summarize()).isEmpty()
    }

    @Test
    fun `circular buffer respects max samples`() {
        var now = 0L
        val recorder = LatencyRecorder(maxSamplesPerEvent = 3, clock = { now })

        repeat(5) {
            recorder.startSpan(LatencyRecorder.Span.LLM_ROUND_TRIP)
            now += 100_000_000L
            recorder.endSpan(LatencyRecorder.Span.LLM_ROUND_TRIP)
        }

        val summary = recorder.summarize().first()
        assertThat(summary.count).isEqualTo(3) // only last 3 kept
    }

    /**
     * Pins every budget documented in docs/latency-budgets.md. This is
     * the enforcement mechanism CI actually has available: there's no
     * device or emulator in the CI pipeline to measure real wake/STT/
     * TTS/LLM timings against, so this test can't catch a runtime
     * regression — but it does catch someone silently loosening a
     * budget constant, which docs/latency-budgets.md explicitly says
     * "requires a PR to justify". Update both this test and the doc
     * table together if a budget genuinely needs to change.
     */
    @Test
    fun `spans carry the exact budgets documented in latency-budgets md`() {
        assertThat(LatencyRecorder.Span.WAKE_TO_LISTENING.budgetMs).isEqualTo(500L)
        assertThat(LatencyRecorder.Span.STT_DURATION.budgetMs).isEqualTo(5_000L)
        assertThat(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE.budgetMs).isEqualTo(200L)
        assertThat(LatencyRecorder.Span.LLM_ROUND_TRIP.budgetMs).isEqualTo(8_000L)
        assertThat(LatencyRecorder.Span.TTS_PREPARATION.budgetMs).isEqualTo(400L)
        assertThat(LatencyRecorder.Span.TOOL_EXECUTION.budgetMs).isEqualTo(2_000L)
    }

    @Test
    fun `no span budget can be added or removed without updating this guard`() {
        // Forces a deliberate touch to this test file (and, by the KDoc
        // above, docs/latency-budgets.md) whenever a Span is added or
        // removed — the previous test only pins the six known values,
        // it wouldn't fail if a seventh span appeared undocumented.
        assertThat(LatencyRecorder.Span.entries.map { it.name }).containsExactly(
            "WAKE_TO_LISTENING",
            "STT_DURATION",
            "FAST_PATH_TO_RESPONSE",
            "LLM_ROUND_TRIP",
            "TTS_PREPARATION",
            "TOOL_EXECUTION"
        )
    }

    @Test
    fun `budgetViolations counts samples over target`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })

        // 200ms → under WAKE budget (500)
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 200_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        // 800ms → over WAKE budget
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 800_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        // 1500ms → over WAKE budget
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 1_500_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        val violations = recorder.budgetViolations()
        assertThat(violations[LatencyRecorder.Span.WAKE_TO_LISTENING]).isEqualTo(2)
    }

    @Test
    fun `totalMeasurements counts all recorded samples`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        repeat(3) {
            recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
            now += 100_000_000L
            recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        }
        repeat(2) {
            recorder.startSpan(LatencyRecorder.Span.LLM_ROUND_TRIP)
            now += 1_000_000_000L
            recorder.endSpan(LatencyRecorder.Span.LLM_ROUND_TRIP)
        }
        assertThat(recorder.totalMeasurements()).isEqualTo(5L)
    }

    @Test
    fun `independent spans use independent keys`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })

        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING, key = "a")
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING, key = "b")
        now = 500_000_000L
        val a = recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING, key = "a")
        now = 1_000_000_000L
        val b = recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING, key = "b")

        assertThat(a).isEqualTo(500L)
        assertThat(b).isEqualTo(1000L)
    }
}
