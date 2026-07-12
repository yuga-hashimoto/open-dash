package com.opendash.app.tool.alarm

import com.opendash.app.data.db.AlarmDao
import com.opendash.app.data.db.AlarmEntity
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import com.opendash.app.tool.escapeJson
import com.opendash.app.voice.alarm.AlarmOccurrenceCalculator
import com.opendash.app.voice.alarm.AlarmRingtoneController
import com.opendash.app.voice.alarm.AlarmScheduler
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * In-app, recurring (or one-shot) alarms — distinct from the
 * system-clock-app `set_alarm` tool
 * ([com.opendash.app.tool.system.SetAlarmToolExecutor]), which is
 * fire-and-forget and can't be listed/cancelled/snoozed afterward.
 * Backed by [AlarmScheduler] (AlarmManager in production) so an alarm
 * fires even if the app process has been killed, and by [AlarmDao] so
 * alarms survive restart and can be listed/cancelled.
 *
 * [alarmRingtoneController] is only relevant while an alarm is actively
 * ringing: cancelling or snoozing a *currently-firing* alarm needs to
 * silence the live [AlarmRingtoneController]-owned sound, not just
 * touch the DB row / future schedule.
 */
class AlarmToolExecutor(
    private val dao: AlarmDao,
    private val scheduler: AlarmScheduler,
    private val alarmRingtoneController: AlarmRingtoneController,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "set_recurring_alarm",
            description = "Set an in-app alarm for a time of day, optionally repeating on specific days. " +
                "Unlike set_alarm (which just opens the system clock app), this alarm can be listed, " +
                "cancelled, or snoozed afterward.",
            parameters = mapOf(
                "hour" to ToolParameter("number", "Hour 0-23", required = true),
                "minute" to ToolParameter("number", "Minute 0-59", required = true),
                "repeat_days" to ToolParameter(
                    "string",
                    "Comma-separated days to repeat on (mon,tue,wed,thu,fri,sat,sun). Omit for a one-shot alarm.",
                    required = false
                ),
                "label" to ToolParameter("string", "Optional alarm label", required = false)
            )
        ),
        ToolSchema(
            name = "list_alarms",
            description = "List all in-app alarms and their next scheduled trigger time.",
            parameters = emptyMap()
        ),
        ToolSchema(
            name = "cancel_alarm",
            description = "Cancel an in-app alarm by its id.",
            parameters = mapOf(
                "id" to ToolParameter("string", "Alarm id", required = true)
            )
        ),
        ToolSchema(
            name = "snooze_alarm",
            description = "Snooze an in-app alarm by a number of minutes (default 9) without changing its saved schedule.",
            parameters = mapOf(
                "id" to ToolParameter("string", "Alarm id", required = true),
                "minutes" to ToolParameter("number", "Minutes to snooze for (default 9)", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "set_recurring_alarm" -> executeSet(call)
            "list_alarms" -> executeList(call)
            "cancel_alarm" -> executeCancel(call)
            "snooze_alarm" -> executeSnooze(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Alarm tool failed")
        ToolResult(call.id, false, "", e.message ?: "Alarm error")
    }

    private suspend fun executeSet(call: ToolCall): ToolResult {
        val hour = (call.arguments["hour"] as? Number)?.toInt()
            ?: return ToolResult(call.id, false, "", "Missing hour")
        val minute = (call.arguments["minute"] as? Number)?.toInt()
            ?: return ToolResult(call.id, false, "", "Missing minute")
        if (hour !in 0..23) return ToolResult(call.id, false, "", "hour out of range: $hour")
        if (minute !in 0..59) return ToolResult(call.id, false, "", "minute out of range: $minute")

        val repeatDaysRaw = (call.arguments["repeat_days"] as? String)?.trim().orEmpty()
        val repeatDays = if (repeatDaysRaw.isEmpty()) {
            emptySet()
        } else {
            val parsed = repeatDaysRaw.split(',').mapNotNull { parseDay(it.trim()) }.toSet()
            if (parsed.size != repeatDaysRaw.split(',').size) {
                return ToolResult(call.id, false, "", "Unrecognized day in repeat_days: $repeatDaysRaw")
            }
            parsed
        }
        val label = (call.arguments["label"] as? String)?.trim().orEmpty()

        val id = "alarm_${UUID.randomUUID().toString().take(8)}"
        val mask = AlarmOccurrenceCalculator.daysToMask(repeatDays)
        dao.upsert(AlarmEntity(id, hour, minute, mask, label))
        val triggerAtMs = AlarmOccurrenceCalculator.nextTriggerMillis(nowProvider(), hour, minute, repeatDays)
        scheduler.schedule(id, label, hour, minute, mask, triggerAtMs)

        return ToolResult(
            call.id, true,
            """{"id":"$id","hour":$hour,"minute":${"%02d".format(minute)},"repeat_days":"${repeatDaysRaw.escapeJson()}","label":"${label.escapeJson()}"}"""
        )
    }

    private suspend fun executeList(call: ToolCall): ToolResult {
        val alarms = dao.listAll()
        val now = nowProvider()
        val data = alarms.joinToString(",") { a ->
            val days = AlarmOccurrenceCalculator.maskToDays(a.repeatDaysMask)
            val nextTriggerMs = AlarmOccurrenceCalculator.nextTriggerMillis(now, a.hour, a.minute, days)
            """{"id":"${a.id}","hour":${a.hour},"minute":${"%02d".format(a.minute)},"repeat_days":"${formatDays(days)}","label":"${a.label.escapeJson()}","next_trigger_ms":$nextTriggerMs}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private suspend fun executeCancel(call: ToolCall): ToolResult {
        val id = call.arguments["id"] as? String
            ?: return ToolResult(call.id, false, "", "Missing id")
        dao.get(id) ?: return ToolResult(call.id, false, "", "No alarm with id $id")
        dao.delete(id)
        scheduler.cancel(id)
        alarmRingtoneController.stopRinging(id)
        return ToolResult(call.id, true, """{"cancelled":"$id"}""")
    }

    private suspend fun executeSnooze(call: ToolCall): ToolResult {
        val id = call.arguments["id"] as? String
            ?: return ToolResult(call.id, false, "", "Missing id")
        val minutes = (call.arguments["minutes"] as? Number)?.toInt() ?: DEFAULT_SNOOZE_MINUTES
        if (minutes !in 1..120) return ToolResult(call.id, false, "", "minutes out of range: $minutes")

        val alarm = dao.get(id) ?: return ToolResult(call.id, false, "", "No alarm with id $id")
        val snoozeUntilMs = nowProvider().plusMinutes(minutes.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduler.schedule(alarm.id, alarm.label, alarm.hour, alarm.minute, alarm.repeatDaysMask, snoozeUntilMs)
        alarmRingtoneController.stopRinging(id)

        return ToolResult(call.id, true, """{"id":"$id","snoozed_minutes":$minutes}""")
    }

    private fun parseDay(token: String): DayOfWeek? = when (token.lowercase()) {
        "mon", "monday" -> DayOfWeek.MONDAY
        "tue", "tues", "tuesday" -> DayOfWeek.TUESDAY
        "wed", "wednesday" -> DayOfWeek.WEDNESDAY
        "thu", "thur", "thurs", "thursday" -> DayOfWeek.THURSDAY
        "fri", "friday" -> DayOfWeek.FRIDAY
        "sat", "saturday" -> DayOfWeek.SATURDAY
        "sun", "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun formatDays(days: Set<DayOfWeek>): String =
        days.sortedBy { it.value }.joinToString(",") { it.name.lowercase().take(3) }

    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 9
    }
}
