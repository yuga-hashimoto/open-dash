package com.opendash.app.voice.reminder

/**
 * Schedules and cancels the OS-level alarm backing a reminder, so it
 * fires even if the app process has been killed. Abstracted so
 * [com.opendash.app.tool.reminder.ReminderToolExecutor] is unit-testable
 * without a real [android.app.AlarmManager].
 */
interface ReminderScheduler {
    fun schedule(id: String, text: String, triggerAtMs: Long)
    fun cancel(id: String)
}
