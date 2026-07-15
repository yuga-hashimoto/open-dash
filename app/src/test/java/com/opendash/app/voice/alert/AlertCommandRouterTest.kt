package com.opendash.app.voice.alert

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AlertCommandRouterTest {

    private val router = AlertCommandRouter()

    private val timer = RingingAlert(id = "timer_1", kind = AlertKind.TIMER, label = "pasta")
    private val alarm = RingingAlert(id = "alarm_1", kind = AlertKind.ALARM, label = "wake")

    @Test
    fun `english stop routes to single ringing timer`() {
        val result = router.route("stop", listOf(timer))
        assertThat(result).isEqualTo(AlertCommand.Stop(timer))
    }

    @Test
    fun `english cancel routes to single ringing alarm`() {
        val result = router.route("cancel", listOf(alarm))
        assertThat(result).isEqualTo(AlertCommand.Stop(alarm))
    }

    @Test
    fun `english snooze routes to single ringing alarm`() {
        val result = router.route("snooze", listOf(alarm))
        assertThat(result).isEqualTo(AlertCommand.Snooze(alarm))
    }

    @Test
    fun `japanese stop phrases route without wake word or id`() {
        listOf("止めて", "やめて", "キャンセル", "ストップ").forEach { phrase ->
            val result = router.route(phrase, listOf(timer))
            assertThat(result).isEqualTo(AlertCommand.Stop(timer))
        }
    }

    @Test
    fun `japanese snooze routes to single ringing alarm`() {
        val result = router.route("スヌーズ", listOf(alarm))
        assertThat(result).isEqualTo(AlertCommand.Snooze(alarm))
    }

    @Test
    fun `unqualified stop with multiple ringing alerts asks for clarification`() {
        val result = router.route("stop", listOf(timer, alarm))
        assertThat(result).isEqualTo(
            AlertCommand.Clarify(alerts = listOf(timer, alarm), intent = AlertIntent.STOP)
        )
    }

    @Test
    fun `unqualified snooze with multiple ringing alarms asks for clarification`() {
        val alarm2 = RingingAlert(id = "alarm_2", kind = AlertKind.ALARM, label = "meds")
        val result = router.route("snooze", listOf(alarm, alarm2))
        assertThat(result).isEqualTo(
            AlertCommand.Clarify(alerts = listOf(alarm, alarm2), intent = AlertIntent.SNOOZE)
        )
    }

    @Test
    fun `snooze against a single ringing timer is unsupported`() {
        val result = router.route("snooze", listOf(timer))
        assertThat(result).isEqualTo(AlertCommand.SnoozeUnsupported(timer))
    }

    @Test
    fun `no ringing alerts returns nothing ringing`() {
        assertThat(router.route("stop", emptyList())).isEqualTo(AlertCommand.NothingRinging)
    }

    @Test
    fun `unrelated utterance is no match`() {
        assertThat(router.route("what's the weather", listOf(timer)))
            .isEqualTo(AlertCommand.NoMatch)
    }

    @Test
    fun `explicit timer cancel phrases are no match so existing fast-path keeps ownership`() {
        // Preserve CancelTimerByLabelMatcher / CancelAllTimersMatcher paths.
        listOf(
            "cancel the pasta timer",
            "stop all timers",
            "タイマー全部止めて",
            "cancel pasta のタイマー",
        ).forEach { phrase ->
            assertThat(router.route(phrase, listOf(timer))).isEqualTo(AlertCommand.NoMatch)
        }
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        assertThat(router.route("  STOP  ", listOf(timer))).isEqualTo(AlertCommand.Stop(timer))
        assertThat(router.route("Cancel.", listOf(alarm))).isEqualTo(AlertCommand.Stop(alarm))
    }
}
