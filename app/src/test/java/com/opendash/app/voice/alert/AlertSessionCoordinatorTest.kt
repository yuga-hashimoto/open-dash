package com.opendash.app.voice.alert

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.system.TimerInfo
import com.opendash.app.tool.system.TimerManager
import com.opendash.app.voice.alarm.AlarmRingtoneController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertSessionCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `single ringing timer stop cancels timer and resumes wake word`() = runTest(dispatcher) {
        val captures = ArrayDeque(listOf("stop"))
        val cancelled = mutableListOf<String>()
        var resumed = 0

        val inventory = inventory(
            ringingAlarmIds = emptySet(),
            firingTimers = listOf(TimerInfo("timer_1", "pasta", 0, 60, isFiring = true)),
        )
        val executor = AlertCommandExecutor(
            stopAlarm = { false },
            cancelTimer = { id -> cancelled += id; true },
            snoozeAlarm = { _, _ -> false },
            speak = {},
            stillRinging = { false },
        )
        val coordinator = AlertSessionCoordinator(
            scope = TestScope(dispatcher),
            inventory = inventory,
            executor = executor,
            captureUtterance = { captures.removeFirstOrNull() },
            resumeWakeWord = { resumed += 1 },
        )

        coordinator.notifyRingingChanged()
        advanceUntilIdle()

        assertThat(cancelled).containsExactly("timer_1")
        assertThat(resumed).isEqualTo(1)
    }

    @Test
    fun `multiple ringing alerts refuse arbitrary cancel and resume after retry budget`() = runTest(dispatcher) {
        val captures = ArrayDeque(listOf("stop", "stop"))
        val cancelled = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var resumed = 0

        val inventory = inventory(
            ringingAlarmIds = setOf("alarm_1"),
            firingTimers = listOf(TimerInfo("timer_1", "pasta", 0, 60, isFiring = true)),
        )
        val executor = AlertCommandExecutor(
            stopAlarm = { false },
            cancelTimer = { id -> cancelled += id; true },
            snoozeAlarm = { _, _ -> false },
            speak = { spoken += it },
            stillRinging = { true },
        )
        val coordinator = AlertSessionCoordinator(
            scope = TestScope(dispatcher),
            inventory = inventory,
            executor = executor,
            captureUtterance = { captures.removeFirstOrNull() },
            resumeWakeWord = { resumed += 1 },
        )

        coordinator.notifyRingingChanged()
        advanceUntilIdle()

        assertThat(cancelled).isEmpty()
        assertThat(spoken).isNotEmpty()
        assertThat(resumed).isEqualTo(1)
    }

    @Test
    fun `no utterance after budget returns to wake word without cancelling`() = runTest(dispatcher) {
        val cancelled = mutableListOf<String>()
        var resumed = 0
        val inventory = inventory(
            ringingAlarmIds = setOf("alarm_1"),
            firingTimers = emptyList(),
        )
        val executor = AlertCommandExecutor(
            stopAlarm = { false },
            cancelTimer = { id -> cancelled += id; true },
            snoozeAlarm = { _, _ -> false },
            speak = {},
            stillRinging = { true },
        )
        val coordinator = AlertSessionCoordinator(
            scope = TestScope(dispatcher),
            inventory = inventory,
            executor = executor,
            captureUtterance = { null },
            resumeWakeWord = { resumed += 1 },
        )

        coordinator.notifyRingingChanged()
        advanceUntilIdle()

        assertThat(cancelled).isEmpty()
        assertThat(resumed).isEqualTo(1)
    }

    private fun inventory(
        ringingAlarmIds: Set<String>,
        firingTimers: List<TimerInfo>,
    ): RingingAlertInventory {
        val alarms = mockk<AlarmRingtoneController>()
        every { alarms.ringingIds() } returns ringingAlarmIds
        val timers = mockk<TimerManager>()
        coEvery { timers.getActiveTimers() } returns firingTimers
        return RingingAlertInventory(alarms, timers)
    }
}
