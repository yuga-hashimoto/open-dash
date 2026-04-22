package com.opendash.app.tool.composite

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema

/**
 * "Evening / wind-down briefing" — pulls unread notifications, tomorrow's
 * calendar events, and the timer state into a single payload so the user
 * can scan their day-end state with one tap or one voice query.
 *
 * Mirrors MorningBriefingTool. Lambda-injected ToolExecutor avoids a Hilt
 * cycle since this composite is part of the same CompositeToolExecutor.
 */
class EveningBriefingTool(
    private val executor: () -> ToolExecutor
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "evening_briefing",
            description = "Compose an evening / wind-down briefing — runs list_notifications, get_calendar_events, and get_timers and returns a combined JSON payload.",
            parameters = mapOf(
                "include_notifications" to ToolParameter(
                    "boolean", "Default true", required = false
                ),
                "include_calendar" to ToolParameter(
                    "boolean", "Default true", required = false
                ),
                "include_timers" to ToolParameter(
                    "boolean", "Default true", required = false
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != "evening_briefing") {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val includeNotifications = call.arguments["include_notifications"] as? Boolean ?: true
        val includeCalendar = call.arguments["include_calendar"] as? Boolean ?: true
        val includeTimers = call.arguments["include_timers"] as? Boolean ?: true

        val json = ParallelSubTools.runParallel(
            inner = executor(),
            idPrefix = "eb",
            logTag = "evening_briefing",
            subs = listOf(
                ParallelSubTools.Sub(includeNotifications, "notifications", "list_notifications"),
                ParallelSubTools.Sub(includeCalendar, "calendar", "get_calendar_events"),
                ParallelSubTools.Sub(includeTimers, "timers", "get_timers")
            )
        )
        return ToolResult(call.id, true, json)
    }
}
