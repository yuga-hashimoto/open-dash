package com.opendash.app.voice.alarm

/**
 * Schedules and cancels the OS-level alarm backing an in-app recurring
 * alarm, so it fires even if the app process has been killed.
 * Abstracted so [com.opendash.app.tool.alarm.AlarmToolExecutor] is
 * unit-testable without a real [android.app.AlarmManager].
 */
interface AlarmScheduler {
    fun schedule(id: String, label: String, hour: Int, minute: Int, repeatDaysMask: Int, triggerAtMs: Long)
    fun cancel(id: String)
}
