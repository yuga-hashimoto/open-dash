package com.opendash.app.voice.metrics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class LatencyAssertionsTest {

    @Test
    fun `checkSpanRecorded passes when at least one sample exists`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 100_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        val result = LatencyAssertions.checkSpanRecorded(
            recorder, LatencyRecorder.Span.WAKE_TO_LISTENING
        )
        assertThat(result).isNull()
    }

    @Test
    fun `checkSpanRecorded fails when no samples exist`() {
        val recorder = LatencyRecorder()

        val result = LatencyAssertions.checkSpanRecorded(
            recorder, LatencyRecorder.Span.FAST_PATH_TO_RESPONSE
        )
        assertThat(result).isNotNull()
        assertThat(result!!).contains("FAST_PATH_TO_RESPONSE")
        assertThat(result).contains("none")
    }

    @Test
    fun `checkNoBudgetViolations passes when every sample is within budget`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        // 200ms < 500ms WAKE_TO_LISTENING budget
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 200_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        val result = LatencyAssertions.checkNoBudgetViolations(
            recorder, LatencyRecorder.Span.WAKE_TO_LISTENING
        )
        assertThat(result).isNull()
    }

    @Test
    fun `checkNoBudgetViolations fails when any sample exceeds budget`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        // 800ms > 500ms WAKE_TO_LISTENING budget
        recorder.startSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)
        now += 800_000_000L
        recorder.endSpan(LatencyRecorder.Span.WAKE_TO_LISTENING)

        val result = LatencyAssertions.checkNoBudgetViolations(
            recorder, LatencyRecorder.Span.WAKE_TO_LISTENING
        )
        assertThat(result).isNotNull()
        assertThat(result!!).contains("WAKE_TO_LISTENING")
        assertThat(result).contains("500ms")
        assertThat(result).contains("1 time(s)")
    }

    @Test
    fun `checkBudgetViolationRateAtMost passes when rate is at or below threshold`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        // 5 within budget + 1 over → 1/6 = 0.166...
        repeat(5) {
            recorder.startSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
            now += 100_000_000L  // 100ms < 200 budget
            recorder.endSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
        }
        recorder.startSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
        now += 300_000_000L  // 300ms > 200 budget
        recorder.endSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)

        // Allow 25% — 1/6 = 16.7% passes
        val result = LatencyAssertions.checkBudgetViolationRateAtMost(
            recorder, LatencyRecorder.Span.FAST_PATH_TO_RESPONSE, maxRate = 0.25
        )
        assertThat(result).isNull()
    }

    @Test
    fun `checkBudgetViolationRateAtMost fails when rate exceeds threshold`() {
        var now = 0L
        val recorder = LatencyRecorder(clock = { now })
        // 1 within budget + 3 over → 3/4 = 0.75
        recorder.startSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
        now += 100_000_000L
        recorder.endSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
        repeat(3) {
            recorder.startSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
            now += 500_000_000L
            recorder.endSpan(LatencyRecorder.Span.FAST_PATH_TO_RESPONSE)
        }

        val result = LatencyAssertions.checkBudgetViolationRateAtMost(
            recorder, LatencyRecorder.Span.FAST_PATH_TO_RESPONSE, maxRate = 0.5
        )
        assertThat(result).isNotNull()
        assertThat(result!!).contains("3/4")
        assertThat(result).contains("75%")
        assertThat(result).contains("max allowed 50%")
    }

    @Test
    fun `checkBudgetViolationRateAtMost passes silently when no samples recorded`() {
        val recorder = LatencyRecorder()
        val result = LatencyAssertions.checkBudgetViolationRateAtMost(
            recorder, LatencyRecorder.Span.LLM_ROUND_TRIP, maxRate = 0.0
        )
        // No samples → nothing to violate.
        assertThat(result).isNull()
    }

    @Test
    fun `checkBudgetViolationRateAtMost rejects out-of-range rate`() {
        val recorder = LatencyRecorder()
        try {
            LatencyAssertions.checkBudgetViolationRateAtMost(
                recorder, LatencyRecorder.Span.WAKE_TO_LISTENING, maxRate = 1.5
            )
            fail("Expected IllegalArgumentException for out-of-range maxRate")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("[0,1]")
        }
    }
}
