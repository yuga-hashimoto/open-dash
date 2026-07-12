package com.opendash.app.service

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.routine.Routine
import com.opendash.app.assistant.routine.RoutineSchedule
import com.opendash.app.assistant.routine.RoutineStore
import com.opendash.app.data.db.AlarmDao
import com.opendash.app.data.db.AlarmEntity
import com.opendash.app.data.db.ReminderDao
import com.opendash.app.data.db.ReminderEntity
import com.opendash.app.voice.alarm.AlarmScheduler
import com.opendash.app.voice.reminder.ReminderScheduler
import com.opendash.app.voice.routine.RoutineScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class BootReschedulerTest {

    private lateinit var alarmDao: AlarmDao
    private lateinit var reminderDao: ReminderDao
    private lateinit var routineStore: RoutineStore
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var routineScheduler: RoutineScheduler
    private lateinit var rescheduler: BootRescheduler
    private val fixedNow = LocalDateTime.of(2024, 1, 1, 8, 0) // Monday 08:00
    private val fixedNowMs = 1_700_000_000_000L

    @BeforeEach
    fun setup() {
        alarmDao = mockk(relaxed = true)
        reminderDao = mockk(relaxed = true)
        routineStore = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        routineScheduler = mockk(relaxed = true)
        rescheduler = BootRescheduler(
            alarmDao, reminderDao, routineStore,
            alarmScheduler, reminderScheduler, routineScheduler,
            nowProvider = { fixedNow }, clock = { fixedNowMs }
        )
        coEvery { alarmDao.listAll() } returns emptyList()
        coEvery { reminderDao.listUpcoming(any()) } returns emptyList()
        coEvery { routineStore.listAll() } returns emptyList()
    }

    @Test
    fun `reschedules every stored alarm`() = runTest {
        coEvery { alarmDao.listAll() } returns listOf(
            AlarmEntity("a1", 7, 0, 21, "weekday alarm"), // mon,wed,fri mask
            AlarmEntity("a2", 9, 30, 0, "one-shot alarm")
        )

        rescheduler.rescheduleAll()

        coVerify { alarmScheduler.schedule("a1", "weekday alarm", 7, 0, 21, any()) }
        coVerify { alarmScheduler.schedule("a2", "one-shot alarm", 9, 30, 0, any()) }
    }

    @Test
    fun `reschedules every upcoming reminder using its stored trigger time`() = runTest {
        coEvery { reminderDao.listUpcoming(fixedNowMs) } returns listOf(
            ReminderEntity("r1", "take out trash", fixedNowMs + 60_000L, fixedNowMs)
        )

        rescheduler.rescheduleAll()

        coVerify { reminderScheduler.schedule("r1", "take out trash", fixedNowMs + 60_000L) }
    }

    @Test
    fun `reschedules only routines that have a schedule`() = runTest {
        coEvery { routineStore.listAll() } returns listOf(
            Routine("r1", "morning", "", emptyList(), schedule = RoutineSchedule(7, 0, 21)),
            Routine("r2", "manual only", "", emptyList(), schedule = null)
        )

        rescheduler.rescheduleAll()

        coVerify { routineScheduler.schedule("r1", "morning", 7, 0, 21, any()) }
        coVerify(exactly = 0) { routineScheduler.schedule("r2", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does nothing when nothing is stored`() = runTest {
        rescheduler.rescheduleAll()

        coVerify(exactly = 0) { alarmScheduler.schedule(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { reminderScheduler.schedule(any(), any(), any()) }
        coVerify(exactly = 0) { routineScheduler.schedule(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failure in one category does not prevent the others from rescheduling`() = runTest {
        coEvery { alarmDao.listAll() } throws RuntimeException("DB error")
        coEvery { reminderDao.listUpcoming(fixedNowMs) } returns listOf(
            ReminderEntity("r1", "take out trash", fixedNowMs + 60_000L, fixedNowMs)
        )

        rescheduler.rescheduleAll()

        coVerify { reminderScheduler.schedule("r1", "take out trash", fixedNowMs + 60_000L) }
    }
}
