package com.opendash.app.service

import com.opendash.app.assistant.routine.RoutineStore
import com.opendash.app.data.db.AlarmDao
import com.opendash.app.data.db.ReminderDao
import com.opendash.app.voice.alarm.AlarmOccurrenceCalculator
import com.opendash.app.voice.alarm.AlarmScheduler
import com.opendash.app.voice.reminder.ReminderScheduler
import com.opendash.app.voice.routine.RoutineScheduler
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Re-arms every persisted alarm, upcoming reminder, and scheduled
 * routine with [AlarmManager][android.app.AlarmManager] after a device
 * reboot — exact alarms don't survive reboot, so without this, a
 * scheduled item silently stops firing until the user re-touches it.
 *
 * Extracted from [BootReceiver] so the rescheduling logic is
 * unit-testable without a real [android.content.BroadcastReceiver]
 * lifecycle, matching this app's existing pattern for
 * [com.opendash.app.voice.alarm.AlarmFireReceiver] /
 * [com.opendash.app.voice.routine.RoutineFireReceiver].
 *
 * Each category is rescheduled independently — a failure reading or
 * re-arming one (e.g. a DB error) must not prevent the other two from
 * running.
 */
class BootRescheduler(
    private val alarmDao: AlarmDao,
    private val reminderDao: ReminderDao,
    private val routineStore: RoutineStore,
    private val alarmScheduler: AlarmScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val routineScheduler: RoutineScheduler,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun rescheduleAll() {
        runCatching { rescheduleAlarms() }
            .onFailure { Timber.e(it, "Failed to reschedule alarms after boot") }
        runCatching { rescheduleReminders() }
            .onFailure { Timber.e(it, "Failed to reschedule reminders after boot") }
        runCatching { rescheduleRoutines() }
            .onFailure { Timber.e(it, "Failed to reschedule routines after boot") }
    }

    private suspend fun rescheduleAlarms() {
        val now = nowProvider()
        alarmDao.listAll().forEach { alarm ->
            val days = AlarmOccurrenceCalculator.maskToDays(alarm.repeatDaysMask)
            val triggerAtMs = AlarmOccurrenceCalculator.nextTriggerMillis(now, alarm.hour, alarm.minute, days)
            alarmScheduler.schedule(alarm.id, alarm.label, alarm.hour, alarm.minute, alarm.repeatDaysMask, triggerAtMs)
        }
    }

    private suspend fun rescheduleReminders() {
        // Reminders already store their absolute trigger time — no
        // occurrence math needed, just re-arm the same instant.
        reminderDao.listUpcoming(clock()).forEach { reminder ->
            reminderScheduler.schedule(reminder.id, reminder.text, reminder.triggerAtMs)
        }
    }

    private suspend fun rescheduleRoutines() {
        val now = nowProvider()
        routineStore.listAll().forEach { routine ->
            val schedule = routine.schedule ?: return@forEach
            val days = AlarmOccurrenceCalculator.maskToDays(schedule.repeatDaysMask)
            val triggerAtMs = AlarmOccurrenceCalculator.nextTriggerMillis(now, schedule.hour, schedule.minute, days)
            routineScheduler.schedule(routine.id, routine.name, schedule.hour, schedule.minute, schedule.repeatDaysMask, triggerAtMs)
        }
    }
}
