package com.opendash.app.assistant.routine

/**
 * A named sequence of tool actions the user can invoke or schedule.
 *
 * Examples:
 *   "good_night" → [turn off all lights, lock doors, set alarm 7am]
 *   "coming_home" → [turn on lights, set AC to 22, play music]
 *
 * Routines are persisted user-defined workflows — a simpler cousin of
 * OpenClaw's cron_tool for scheduled tasks.
 */
data class Routine(
    val id: String,
    val name: String,
    val description: String,
    val actions: List<RoutineAction>,
    /** Null means manual-invoke only (the original, still-default behavior). */
    val schedule: RoutineSchedule? = null
)

data class RoutineAction(
    val toolName: String,
    val arguments: Map<String, Any?>,
    /** Optional delay (ms) before this action runs (after the previous). */
    val delayMs: Long = 0L
)

/**
 * A time-of-day (+ optional day-of-week repeat) trigger that runs a
 * routine automatically. [repeatDaysMask] uses the same 7-bit
 * Monday..Sunday convention as
 * [com.opendash.app.voice.alarm.AlarmOccurrenceCalculator] (0 = one-shot
 * next occurrence); the routine scheduler reuses that same calculator.
 */
data class RoutineSchedule(
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int
)
