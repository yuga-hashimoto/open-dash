package com.opendash.app.assistant.proactive

import com.google.common.truth.Truth.assertThat
import com.opendash.app.util.ThermalLevel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ThermalWarningRuleTest {

    private val ctx = ProactiveContext(
        nowMs = 1_700_000_000_000L,
        hourOfDay = 14,
        dayOfWeek = java.util.Calendar.WEDNESDAY,
    )

    private fun rule(level: ThermalLevel) =
        ThermalWarningRule(levelSupplier = { level })

    @Test
    fun `NORMAL thermal state emits nothing`() = runTest {
        val s = rule(ThermalLevel.NORMAL).evaluate(ctx)
        assertThat(s).isNull()
    }

    @Test
    fun `WARM emits NORMAL priority nudge`() = runTest {
        val s = rule(ThermalLevel.WARM).evaluate(ctx)
        assertThat(s).isNotNull()
        assertThat(s!!.priority).isEqualTo(Suggestion.Priority.NORMAL)
        assertThat(s.id).isEqualTo("thermal_warm")
    }

    @Test
    fun `HOT emits HIGH priority urgent nudge`() = runTest {
        val s = rule(ThermalLevel.HOT).evaluate(ctx)
        assertThat(s).isNotNull()
        assertThat(s!!.priority).isEqualTo(Suggestion.Priority.HIGH)
        assertThat(s.id).isEqualTo("thermal_hot")
    }

    @Test
    fun `id stable across evaluations within same bucket`() = runTest {
        val a = rule(ThermalLevel.WARM).evaluate(ctx)
        val b = rule(ThermalLevel.WARM).evaluate(ctx)
        assertThat(a!!.id).isEqualTo(b!!.id)
    }

    @Test
    fun `expiresAt is set so SuggestionState can retire the card`() = runTest {
        val s = rule(ThermalLevel.WARM).evaluate(ctx)
        assertThat(s!!.expiresAtMs).isNotNull()
        assertThat(s.expiresAtMs!!).isGreaterThan(ctx.nowMs)
    }
}
