package com.opendash.app.voice.alert

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AlertCommandExecutorTest {

    private val stoppedAlarms = mutableListOf<String>()
    private val cancelledTimers = mutableListOf<String>()
    private val snoozedAlarms = mutableListOf<Pair<String, Int>>()
    private val spoken = mutableListOf<String>()

    private val executor = AlertCommandExecutor(
        stopAlarm = { id -> stoppedAlarms += id; true },
        cancelTimer = { id -> cancelledTimers += id; true },
        snoozeAlarm = { id, minutes -> snoozedAlarms += id to minutes; true },
        speak = { msg -> spoken += msg },
    )

    private val timer = RingingAlert(id = "timer_1", kind = AlertKind.TIMER, label = "pasta")
    private val alarm = RingingAlert(id = "alarm_1", kind = AlertKind.ALARM, label = "wake")

    @Test
    fun `stop on timer cancels that timer only`() = runTest {
        val outcome = executor.execute(AlertCommand.Stop(timer))
        assertThat(outcome).isEqualTo(AlertCommandOutcome.Handled)
        assertThat(cancelledTimers).containsExactly("timer_1")
        assertThat(stoppedAlarms).isEmpty()
    }

    @Test
    fun `stop on alarm silences ringing without requiring an id from the user`() = runTest {
        val outcome = executor.execute(AlertCommand.Stop(alarm))
        assertThat(outcome).isEqualTo(AlertCommandOutcome.Handled)
        assertThat(stoppedAlarms).containsExactly("alarm_1")
        assertThat(cancelledTimers).isEmpty()
    }

    @Test
    fun `snooze on alarm snoozes and stops sound`() = runTest {
        val outcome = executor.execute(AlertCommand.Snooze(alarm, minutes = 9))
        assertThat(outcome).isEqualTo(AlertCommandOutcome.Handled)
        assertThat(snoozedAlarms).containsExactly("alarm_1" to 9)
        assertThat(stoppedAlarms).containsExactly("alarm_1")
    }

    @Test
    fun `clarify speaks a refusal instead of cancelling arbitrarily`() = runTest {
        val outcome = executor.execute(
            AlertCommand.Clarify(listOf(timer, alarm), AlertIntent.STOP)
        )
        assertThat(outcome).isInstanceOf(AlertCommandOutcome.NeedsClarification::class.java)
        assertThat(spoken.single()).contains("Multiple")
        assertThat(cancelledTimers).isEmpty()
        assertThat(stoppedAlarms).isEmpty()
    }

    @Test
    fun `snooze unsupported speaks and does not cancel`() = runTest {
        val outcome = executor.execute(AlertCommand.SnoozeUnsupported(timer))
        assertThat(outcome).isInstanceOf(AlertCommandOutcome.NeedsClarification::class.java)
        assertThat(spoken.single().lowercase()).contains("timer")
        assertThat(cancelledTimers).isEmpty()
    }

    @Test
    fun `no match does not touch alerts`() = runTest {
        val outcome = executor.execute(AlertCommand.NoMatch)
        assertThat(outcome).isInstanceOf(AlertCommandOutcome.NoMatch::class.java)
        assertThat(cancelledTimers).isEmpty()
        assertThat(stoppedAlarms).isEmpty()
    }
}
