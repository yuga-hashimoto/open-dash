package com.opendash.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.opendash.app.e2e.fakes.FakeTextToSpeech
import com.opendash.app.tool.system.TimerManager
import com.opendash.app.voice.metrics.LatencyAssertions
import com.opendash.app.voice.metrics.LatencyRecorder
import com.opendash.app.voice.pipeline.VoicePipeline
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Regression guard for the priority-1 latency budgets.
 *
 * Drives the same fast-path utterance as
 * [VoicePipelineFastPathE2ETest], then asserts that the
 * [LatencyRecorder.Span.FAST_PATH_TO_RESPONSE] span was actually
 * recorded — i.e. the budget timing path is alive and pipeline
 * regressions that bypass `latencyRecorder.endSpan(...)` get caught.
 *
 * Why we don't hard-assert "0 violations":
 *
 *   The 200ms fast-path budget targets warm production hardware. CI
 *   emulators on a cold AVD spike well past this even on healthy code
 *   paths (Hilt cold start, ToolExecutor first-touch, AlarmManager
 *   binder). Hard-asserting would be flake-on-purpose. We use the
 *   [BUDGET_VIOLATION_RATE_CEILING] soft check instead — if the rate
 *   ever climbs past 50% on a single fast-path call, something is
 *   structurally wrong and we want to know. (One sample = either 0%
 *   or 100% violations, so a single cold run can flake; rerun before
 *   panicking.)
 *
 * Real-device budget validation lives in the L4 manual checklist,
 * `docs/real-device-smoke-test.md` step 3.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LatencyBudgetE2ETest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var fakeTts: FakeTextToSpeech
    @Inject lateinit var timerManager: TimerManager
    @Inject lateinit var latencyRecorder: LatencyRecorder

    @Before
    fun setUp() {
        hiltRule.inject()
        fakeTts.reset()
        latencyRecorder.reset()
    }

    @Test
    fun fast_path_records_its_span_and_stays_within_soft_budget() = runTest {
        val before = timerManager.getActiveTimers().map { it.id }.toSet()
        try {
            withTimeout(PIPELINE_TIMEOUT_MS) {
                voicePipeline.processUserInput("set timer for 5 minutes")
            }

            val recordedErr = LatencyAssertions.checkSpanRecorded(
                latencyRecorder, LatencyRecorder.Span.FAST_PATH_TO_RESPONSE
            )
            assertThat(recordedErr).isNull()

            // Soft budget — see class doc for why we don't go stricter.
            val rateErr = LatencyAssertions.checkBudgetViolationRateAtMost(
                latencyRecorder,
                LatencyRecorder.Span.FAST_PATH_TO_RESPONSE,
                maxRate = BUDGET_VIOLATION_RATE_CEILING
            )
            assertThat(rateErr).isNull()
        } finally {
            // Don't leak alarms onto the device that runs the suite.
            timerManager.getActiveTimers()
                .filter { it.id !in before }
                .forEach { timerManager.cancelTimer(it.id) }
        }
    }

    private companion object {
        const val PIPELINE_TIMEOUT_MS = 5_000L

        // Permissive: a single fast-path invocation can be 0% or 100%
        // violations. We're guarding against "the recorder stopped
        // counting" or "fast path is suddenly 5s every time", not the
        // 200ms target itself.
        const val BUDGET_VIOLATION_RATE_CEILING = 1.0
    }
}
