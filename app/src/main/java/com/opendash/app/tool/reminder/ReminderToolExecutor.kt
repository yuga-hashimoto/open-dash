package com.opendash.app.tool.reminder

import com.opendash.app.data.db.ReminderDao
import com.opendash.app.data.db.ReminderEntity
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import com.opendash.app.voice.reminder.ReminderScheduler
import timber.log.Timber
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * One-time, time-triggered reminders ("remind me in 20 minutes to take
 * out the trash") — distinct from calendar events (no attendees/
 * location/recurrence) and from timers (wall-clock time, not a
 * countdown duration). Backed by [ReminderScheduler] (AlarmManager in
 * production) so a reminder fires even if the app process has been
 * killed, and by [ReminderDao] so reminders survive restart and can be
 * listed/cancelled.
 */
class ReminderToolExecutor(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "set_reminder",
            description = "Set a one-time reminder that notifies the user at a specific future time, even if the app isn't open.",
            parameters = mapOf(
                "text" to ToolParameter("string", "What to remind the user about", required = true),
                "trigger_time" to ToolParameter(
                    "string",
                    "When to fire, local time as yyyy-MM-dd'T'HH:mm:ss or yyyy-MM-dd HH:mm",
                    required = true
                )
            )
        ),
        ToolSchema(
            name = "list_reminders",
            description = "List all upcoming (not yet triggered) reminders.",
            parameters = emptyMap()
        ),
        ToolSchema(
            name = "cancel_reminder",
            description = "Cancel a reminder by its id.",
            parameters = mapOf(
                "id" to ToolParameter("string", "Reminder id", required = true)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "set_reminder" -> executeSet(call)
            "list_reminders" -> executeList(call)
            "cancel_reminder" -> executeCancel(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Reminder tool failed")
        ToolResult(call.id, false, "", e.message ?: "Reminder error")
    }

    private suspend fun executeSet(call: ToolCall): ToolResult {
        val text = (call.arguments["text"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing text")
        val triggerTimeRaw = call.arguments["trigger_time"] as? String
            ?: return ToolResult(call.id, false, "", "Missing trigger_time")

        val triggerAtMs = parseTime(triggerTimeRaw)
            ?: return ToolResult(
                call.id, false, "",
                "Invalid trigger_time format, expected yyyy-MM-dd'T'HH:mm:ss"
            )
        if (triggerAtMs <= clock()) {
            return ToolResult(call.id, false, "", "trigger_time must be in the future")
        }

        val id = "reminder_${UUID.randomUUID().toString().take(8)}"
        dao.upsert(ReminderEntity(id, text, triggerAtMs, clock()))
        scheduler.schedule(id, text, triggerAtMs)
        return ToolResult(
            call.id, true,
            """{"id":"$id","text":"${text.escapeJson()}","trigger_time":"${triggerTimeRaw.escapeJson()}"}"""
        )
    }

    private suspend fun executeList(call: ToolCall): ToolResult {
        val reminders = dao.listUpcoming(clock())
        val data = reminders.joinToString(",") { r ->
            """{"id":"${r.id}","text":"${r.text.escapeJson()}","trigger_at_ms":${r.triggerAtMs}}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private suspend fun executeCancel(call: ToolCall): ToolResult {
        val id = call.arguments["id"] as? String
            ?: return ToolResult(call.id, false, "", "Missing id")
        dao.get(id) ?: return ToolResult(call.id, false, "", "No reminder with id $id")
        dao.delete(id)
        scheduler.cancel(id)
        return ToolResult(call.id, true, """{"cancelled":"$id"}""")
    }

    private fun parseTime(value: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                    isLenient = false
                }
                return fmt.parse(value)?.time
            } catch (_: ParseException) {
                // try next
            }
        }
        return null
    }

    private fun String.escapeJson(): String = buildString(length) {
        for (c in this@escapeJson) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
