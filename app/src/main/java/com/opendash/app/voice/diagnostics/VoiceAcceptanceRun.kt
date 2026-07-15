package com.opendash.app.voice.diagnostics

import java.util.UUID

/**
 * Pure, ordered acceptance-run state machine for the physical smoke test.
 * Observation is evidence that an event was recorded; verification is an
 * explicit operator verdict and is never inferred from observation.
 */
class VoiceAcceptanceRun(
    private val requiredSteps: List<String> = DEFAULT_STEPS,
    private val sanitize: (String?) -> String? = ::sanitizeNote,
) {
    data class Step(
        val id: String,
        val observedAtMs: Long? = null,
        val observation: String? = null,
        val verifiedAtMs: Long? = null,
        val passed: Boolean? = null,
        val verdict: String? = null,
    )

    data class Snapshot(
        val runId: String?,
        val startedAtMs: Long?,
        val completedAtMs: Long?,
        val steps: List<Step>,
    ) {
        val isComplete: Boolean
            get() = steps.isNotEmpty() && steps.all { it.observedAtMs != null }

        val isVerified: Boolean
            get() = isComplete && steps.all { it.passed != null }
    }

    sealed class Result {
        data object Recorded : Result()
        data object Verified : Result()
        data object Duplicate : Result()
        data object OutOfOrder : Result()
        data object UnknownStep : Result()
        data object NotStarted : Result()
        data object AlreadyVerified : Result()
    }

    private var runId: String? = null
    private var startedAtMs: Long? = null
    private var completedAtMs: Long? = null
    private val steps = requiredSteps.distinct().associateWith { Step(it) }.toMutableMap()

    fun start(nowMs: Long = System.currentTimeMillis(), id: String = UUID.randomUUID().toString()): Snapshot {
        runId = id
        startedAtMs = nowMs
        completedAtMs = null
        requiredSteps.forEach { steps[it] = Step(it) }
        return snapshot()
    }

    fun observe(stepId: String, note: String? = null, nowMs: Long = System.currentTimeMillis()): Result {
        if (runId == null) return Result.NotStarted
        val current = steps[stepId] ?: return Result.UnknownStep
        if (current.observedAtMs != null) return Result.Duplicate
        if (steps.values.any { it.observedAtMs == null && requiredSteps.indexOf(it.id) < requiredSteps.indexOf(stepId) }) {
            return Result.OutOfOrder
        }
        steps[stepId] = current.copy(observedAtMs = nowMs, observation = sanitize(note))
        updateCompletion(nowMs)
        return Result.Recorded
    }

    fun verify(stepId: String, passed: Boolean, verdict: String? = null, nowMs: Long = System.currentTimeMillis()): Result {
        if (runId == null) return Result.NotStarted
        val current = steps[stepId] ?: return Result.UnknownStep
        if (current.observedAtMs == null) return Result.NotStarted
        if (current.passed != null) return Result.AlreadyVerified
        steps[stepId] = current.copy(
            verifiedAtMs = nowMs,
            passed = passed,
            verdict = sanitize(verdict)
        )
        updateCompletion(nowMs)
        return Result.Verified
    }

    fun clear() {
        runId = null
        startedAtMs = null
        completedAtMs = null
        requiredSteps.forEach { steps[it] = Step(it) }
    }

    fun snapshot(): Snapshot = Snapshot(
        runId = runId,
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
        steps = requiredSteps.mapNotNull { steps[it] }
    )

    fun exportText(): String {
        val current = snapshot()
        return buildString {
            appendLine("schema=voice-acceptance-v1")
            appendLine("runId=${current.runId ?: "none"}")
            appendLine("startedAtMs=${current.startedAtMs ?: "none"}")
            appendLine("complete=${current.isComplete}")
            appendLine("verified=${current.isVerified}")
            current.steps.forEach { step ->
                append("step=${step.id}")
                append(" observed=${step.observedAtMs != null}")
                append(" passed=${step.passed ?: "unverified"}")
                step.observation?.let { append(" observation=$it") }
                step.verdict?.let { append(" verdict=$it") }
                appendLine()
            }
        }
    }

    fun observeStepIds(): List<String> = requiredSteps

    private fun updateCompletion(nowMs: Long) {
        if (snapshot().isComplete) completedAtMs = completedAtMs ?: nowMs
    }

    companion object {
        val DEFAULT_STEPS = listOf(
            "cold_start",
            "wake_latency",
            "fast_path",
            "tool_path",
            "barge_in",
            "alert_stop",
            "error_recovery",
            "tablet_layout",
            "permissions",
            "stability",
            "system_info",
            "degraded_recovery",
            "boot_recovery",
        )

        private fun sanitizeNote(value: String?): String? {
            val normalized = value?.replace(Regex("\\s+"), " ")?.trim()?.take(160) ?: return null
            if (normalized.contains("Bearer ", ignoreCase = true) ||
                normalized.contains("api_key", ignoreCase = true) ||
                normalized.contains("token=", ignoreCase = true)
            ) return "[REDACTED]"
            return normalized
        }
    }
}
