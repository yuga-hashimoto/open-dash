package com.opendash.app.voice.routine

/**
 * Schedules and cancels the OS-level alarm that triggers a routine
 * automatically, so it fires even if the app process has been killed.
 * Abstracted so [com.opendash.app.assistant.routine.RoutineToolExecutor]
 * is unit-testable without a real [android.app.AlarmManager].
 */
interface RoutineScheduler {
    fun schedule(routineId: String, routineName: String, hour: Int, minute: Int, repeatDaysMask: Int, triggerAtMs: Long)
    fun cancel(routineId: String)
}
