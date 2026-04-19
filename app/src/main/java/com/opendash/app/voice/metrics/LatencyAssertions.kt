package com.opendash.app.voice.metrics

/**
 * Pure-Kotlin assertion helpers for [LatencyRecorder] data, callable
 * from both unit tests (JVM) and instrumented tests (ART). Lives in
 * `main` rather than `test` because instrumented suites and unit suites
 * have separate classpaths.
 *
 * The helpers return a non-null error message when the assertion fails
 * and `null` when it passes; callers wrap that into Truth / JUnit
 * `assertThat(...).isNull()` so the failure message renders inline:
 *
 * ```
 * assertThat(LatencyAssertions.checkSpanRecorded(recorder, span)).isNull()
 * ```
 *
 * This split keeps the helper itself test-framework agnostic.
 */
object LatencyAssertions {

    /**
     * @return null on success, an error message string on failure.
     */
    fun checkSpanRecorded(
        recorder: LatencyRecorder,
        span: LatencyRecorder.Span
    ): String? {
        val summary = recorder.summarize().firstOrNull { it.event == span.name }
        return if (summary != null) null
        else "Expected at least one ${span.name} sample but recorder has none. " +
            "Recorded spans: ${recorder.summarize().map { it.event }}"
    }

    /**
     * Hard budget assertion — every recorded sample for [span] must be
     * within [LatencyRecorder.Span.budgetMs]. Use sparingly: emulator
     * cold-start spikes can flake this even when the production path is
     * healthy. Prefer [checkBudgetViolationRateAtMost] for CI runs.
     *
     * @return null on success, an error message string on failure.
     */
    fun checkNoBudgetViolations(
        recorder: LatencyRecorder,
        span: LatencyRecorder.Span
    ): String? {
        val violations = recorder.budgetViolations()[span] ?: 0
        return if (violations == 0) null
        else "${span.name} exceeded its ${span.budgetMs}ms budget $violations time(s). " +
            "Summary: ${recorder.summarize().firstOrNull { it.event == span.name }}"
    }

    /**
     * Soft budget assertion — at most [maxRate] (0.0..1.0) of recorded
     * samples may exceed the per-span budget. Returns success when no
     * samples are recorded so the helper is safe to call after a setup
     * that may not have hit [span].
     *
     * @return null on success, an error message string on failure.
     */
    fun checkBudgetViolationRateAtMost(
        recorder: LatencyRecorder,
        span: LatencyRecorder.Span,
        maxRate: Double
    ): String? {
        require(maxRate in 0.0..1.0) { "maxRate must be in [0,1], got $maxRate" }
        val total = recorder.summarize().firstOrNull { it.event == span.name }?.count ?: 0
        if (total == 0) return null
        val violations = recorder.budgetViolations()[span] ?: 0
        val rate = violations.toDouble() / total
        return if (rate <= maxRate) null
        else {
            val pct = (rate * 100).toInt()
            val maxPct = (maxRate * 100).toInt()
            "${span.name}: $violations/$total samples (${pct}%) exceeded the " +
                "${span.budgetMs}ms budget; max allowed ${maxPct}%."
        }
    }
}
