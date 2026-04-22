package com.opendash.app.tool.composite

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema

/**
 * One-shot wind-down: turn off all lights, pause media, cancel any active
 * timers. Returns a brief summary of what happened so the LLM (or fast-path
 * caller) can speak it back.
 */
class GoodnightTool(
    private val executor: () -> ToolExecutor
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "goodnight",
            description = "Wind-down: turn off all lights, pause media players, and cancel active timers in one shot.",
            parameters = mapOf(
                "include_lights" to ToolParameter("boolean", "Default true", required = false),
                "include_media" to ToolParameter("boolean", "Default true", required = false),
                "include_timers" to ToolParameter("boolean", "Default true", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != "goodnight") {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val includeLights = call.arguments["include_lights"] as? Boolean ?: true
        val includeMedia = call.arguments["include_media"] as? Boolean ?: true
        val includeTimers = call.arguments["include_timers"] as? Boolean ?: true

        val json = ParallelSubTools.runParallel(
            inner = executor(),
            idPrefix = "gn",
            logTag = "goodnight",
            subs = listOf(
                ParallelSubTools.Sub(
                    includeLights, "lights_off", "execute_command",
                    arguments = mapOf("device_type" to "light", "action" to "turn_off"),
                    render = ParallelSubTools.RenderKind.Success
                ),
                ParallelSubTools.Sub(
                    includeMedia, "media_paused", "execute_command",
                    arguments = mapOf("device_type" to "media_player", "action" to "media_pause"),
                    render = ParallelSubTools.RenderKind.Success
                ),
                ParallelSubTools.Sub(
                    includeTimers, "timers_cancelled", "cancel_all_timers",
                    render = ParallelSubTools.RenderKind.Success
                )
            )
        )
        return ToolResult(call.id, true, json)
    }
}
