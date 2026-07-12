package com.opendash.app.assistant.routine

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import com.opendash.app.tool.escapeJson
import com.opendash.app.voice.alarm.AlarmOccurrenceCalculator
import com.opendash.app.voice.routine.RoutineScheduler
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.UUID

/**
 * Lets the LLM create and run user-defined routines, optionally
 * scheduled to run automatically (day-of-week recurring or one-shot).
 */
class RoutineToolExecutor(
    private val store: RoutineStore,
    private val toolExecutor: ToolExecutor,
    moshi: Moshi,
    private val scheduler: RoutineScheduler,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
) : ToolExecutor {

    private data class RoutineActionInput(
        @Json(name = "tool_name") val toolName: String,
        val arguments: Map<String, Any?> = emptyMap(),
        @Json(name = "delay_ms") val delayMs: Long = 0
    )

    private val actionInputListAdapter: JsonAdapter<List<RoutineActionInput>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, RoutineActionInput::class.java)
    )

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "create_routine",
            description = "Create a named routine — a sequence of tool actions to run together " +
                "(e.g. 'good night': turn off lights, lock doors). Optionally schedule it to run " +
                "automatically at a time of day, optionally repeating on specific days.",
            parameters = mapOf(
                "name" to ToolParameter("string", "Routine name, e.g. 'good night'", required = true),
                "description" to ToolParameter("string", "What this routine does", required = false),
                "actions_json" to ToolParameter(
                    "string",
                    "JSON array of actions: [{\"tool_name\":\"execute_command\",\"arguments\":{...},\"delay_ms\":0}]",
                    required = true
                ),
                "schedule_hour" to ToolParameter(
                    "number", "Hour 0-23 to run automatically. Omit for manual-invoke only.", required = false
                ),
                "schedule_minute" to ToolParameter(
                    "number", "Minute 0-59 to run automatically.", required = false
                ),
                "schedule_repeat_days" to ToolParameter(
                    "string",
                    "Comma-separated days to repeat on (mon,tue,wed,thu,fri,sat,sun). Omit for a one-shot schedule.",
                    required = false
                )
            )
        ),
        ToolSchema(
            name = "run_routine",
            description = "Run a saved routine by name (e.g. 'good night', 'coming home').",
            parameters = mapOf(
                "name" to ToolParameter("string", "Routine name", required = true)
            )
        ),
        ToolSchema(
            name = "list_routines",
            description = "List all saved routines and their actions.",
            parameters = emptyMap()
        ),
        ToolSchema(
            name = "delete_routine",
            description = "Delete a routine by id.",
            parameters = mapOf(
                "id" to ToolParameter("string", "Routine id", required = true)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        return try {
            when (call.name) {
                "create_routine" -> executeCreate(call)
                "run_routine" -> executeRun(call)
                "list_routines" -> executeList(call)
                "delete_routine" -> executeDelete(call)
                else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Routine tool failed")
            ToolResult(call.id, false, "", e.message ?: "Routine error")
        }
    }

    private suspend fun executeCreate(call: ToolCall): ToolResult {
        val name = (call.arguments["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing name")
        val description = (call.arguments["description"] as? String).orEmpty()
        val actionsJsonRaw = call.arguments["actions_json"] as? String
            ?: return ToolResult(call.id, false, "", "Missing actions_json")

        val actionInputs = try {
            actionInputListAdapter.fromJson(actionsJsonRaw)
        } catch (e: Exception) {
            return ToolResult(call.id, false, "", "Invalid actions_json: ${e.message}")
        } ?: return ToolResult(call.id, false, "", "Invalid actions_json")

        val actions = actionInputs.map { RoutineAction(it.toolName, it.arguments, it.delayMs) }

        val scheduleHour = (call.arguments["schedule_hour"] as? Number)?.toInt()
        val scheduleMinute = (call.arguments["schedule_minute"] as? Number)?.toInt()
        val schedule = if (scheduleHour != null && scheduleMinute != null) {
            if (scheduleHour !in 0..23) return ToolResult(call.id, false, "", "schedule_hour out of range: $scheduleHour")
            if (scheduleMinute !in 0..59) return ToolResult(call.id, false, "", "schedule_minute out of range: $scheduleMinute")
            val repeatDaysRaw = (call.arguments["schedule_repeat_days"] as? String)?.trim().orEmpty()
            val repeatDays = parseRepeatDays(repeatDaysRaw)
                ?: return ToolResult(call.id, false, "", "Unrecognized day in schedule_repeat_days: $repeatDaysRaw")
            RoutineSchedule(scheduleHour, scheduleMinute, AlarmOccurrenceCalculator.daysToMask(repeatDays))
        } else {
            null
        }

        val id = "routine_${UUID.randomUUID().toString().take(8)}"
        store.save(Routine(id, name, description, actions, schedule))

        if (schedule != null) {
            val repeatDays = AlarmOccurrenceCalculator.maskToDays(schedule.repeatDaysMask)
            val triggerAtMs = AlarmOccurrenceCalculator.nextTriggerMillis(
                nowProvider(), schedule.hour, schedule.minute, repeatDays
            )
            scheduler.schedule(id, name, schedule.hour, schedule.minute, schedule.repeatDaysMask, triggerAtMs)
        }

        return ToolResult(
            call.id, true,
            """{"id":"$id","name":"${name.escapeJson()}","actions":${actions.size},"scheduled":${schedule != null}}"""
        )
    }

    private fun parseRepeatDays(raw: String): Set<DayOfWeek>? {
        if (raw.isEmpty()) return emptySet()
        val tokens = raw.split(',').map { it.trim() }
        val parsed = tokens.mapNotNull { parseDay(it) }.toSet()
        return if (parsed.size != tokens.size) null else parsed
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

    private suspend fun executeRun(call: ToolCall): ToolResult {
        val name = call.arguments["name"] as? String
            ?: return ToolResult(call.id, false, "", "Missing name")

        val depth = kotlin.coroutines.coroutineContext[RoutineDepth]?.value ?: 0
        if (depth >= MAX_ROUTINE_DEPTH) {
            return ToolResult(call.id, false, "", "Routine nesting too deep (possible cycle involving '$name')")
        }

        val routine = store.getByName(name)
            ?: return ToolResult(call.id, false, "", "Routine not found: $name")

        val results = kotlinx.coroutines.withContext(RoutineDepth(depth + 1)) { runRoutine(routine) }
        val failures = results.count { !it.first }
        val data = """{"routine":"${routine.name}","ran":${results.size},"failures":$failures}"""
        return ToolResult(call.id, failures == 0, data, if (failures > 0) "$failures actions failed" else null)
    }

    /**
     * Tracks how many `run_routine` calls are nested on the current
     * coroutine so a routine that (directly or via a cycle of two or
     * more routines) invokes itself fails fast instead of recursing
     * until a StackOverflowError — `create_routine` lets the LLM author
     * `actions_json` freely, including a `run_routine` action.
     */
    private data class RoutineDepth(val value: Int) : kotlin.coroutines.CoroutineContext.Element {
        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
        companion object Key : kotlin.coroutines.CoroutineContext.Key<RoutineDepth>
    }

    private suspend fun runRoutine(routine: Routine): List<Pair<Boolean, String>> {
        val results = mutableListOf<Pair<Boolean, String>>()
        for (action in routine.actions) {
            if (action.delayMs > 0) delay(action.delayMs)
            val toolCall = ToolCall(
                id = "routine_${routine.id}_${results.size}",
                name = action.toolName,
                arguments = action.arguments
            )
            val result = toolExecutor.execute(toolCall)
            results.add(result.success to result.data)
        }
        return results
    }

    private suspend fun executeList(call: ToolCall): ToolResult {
        val routines = store.listAll()
        val data = routines.joinToString(",") { r ->
            val actions = r.actions.joinToString(",") { a ->
                """{"tool":"${a.toolName.escapeJson()}"}"""
            }
            val schedule = r.schedule?.let {
                """{"hour":${it.hour},"minute":${it.minute},"repeat_days_mask":${it.repeatDaysMask}}"""
            } ?: "null"
            """{"id":"${r.id}","name":"${r.name.escapeJson()}","description":"${r.description.escapeJson()}","actions":[$actions],"schedule":$schedule}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private suspend fun executeDelete(call: ToolCall): ToolResult {
        val id = call.arguments["id"] as? String
            ?: return ToolResult(call.id, false, "", "Missing id")
        val existing = store.get(id)
        return if (store.delete(id)) {
            if (existing?.schedule != null) scheduler.cancel(id)
            ToolResult(call.id, true, """{"deleted":"$id"}""")
        } else {
            ToolResult(call.id, false, "", "No routine with id $id")
        }
    }

    private companion object {
        const val MAX_ROUTINE_DEPTH = 5
    }
}
