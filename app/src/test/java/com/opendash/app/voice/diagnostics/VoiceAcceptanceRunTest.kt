package com.opendash.app.voice.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VoiceAcceptanceRunTest {
    private val steps = listOf("one", "two", "three")

    @Test
    fun `observations must follow required order and verification is explicit`() {
        val run = VoiceAcceptanceRun(steps)
        run.start(nowMs = 10L, id = "run-1")

        assertThat(run.observe("two", nowMs = 20L)).isEqualTo(VoiceAcceptanceRun.Result.OutOfOrder)
        assertThat(run.observe("one", "heard at one meter", 20L)).isEqualTo(VoiceAcceptanceRun.Result.Recorded)
        assertThat(run.observe("one", nowMs = 21L)).isEqualTo(VoiceAcceptanceRun.Result.Duplicate)
        assertThat(run.snapshot().isVerified).isFalse()
        assertThat(run.verify("one", passed = true, verdict = "operator pass", nowMs = 30L))
            .isEqualTo(VoiceAcceptanceRun.Result.Verified)
    }

    @Test
    fun `complete means observed all steps while verified requires every verdict`() {
        val run = VoiceAcceptanceRun(steps)
        run.start(nowMs = 1L, id = "run-2")
        steps.forEachIndexed { index, id -> run.observe(id, nowMs = (index + 1).toLong()) }

        assertThat(run.snapshot().isComplete).isTrue()
        assertThat(run.snapshot().isVerified).isFalse()
        run.verify("one", true)
        run.verify("two", false, "operator fail")
        run.verify("three", true)
        assertThat(run.snapshot().isVerified).isTrue()
    }

    @Test
    fun `unknown and pre-start events are rejected without mutation`() {
        val run = VoiceAcceptanceRun(steps)
        assertThat(run.observe("one")).isEqualTo(VoiceAcceptanceRun.Result.NotStarted)
        run.start(id = "run-3")
        assertThat(run.observe("missing")).isEqualTo(VoiceAcceptanceRun.Result.UnknownStep)
        assertThat(run.verify("one", true)).isEqualTo(VoiceAcceptanceRun.Result.NotStarted)
    }

    @Test
    fun `notes are bounded and secret-shaped values are redacted`() {
        val run = VoiceAcceptanceRun(listOf("one"))
        run.start(id = "run-4")
        run.observe("one", "Bearer super-secret-token")
        assertThat(run.exportText()).contains("observation=[REDACTED]")
        assertThat(run.exportText().length).isLessThan(1000)
    }
}
