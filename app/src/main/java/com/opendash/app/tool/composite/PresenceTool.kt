package com.opendash.app.tool.composite

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema

/**
 * "I'm home" / "leaving" presence shortcuts. Mirror Alexa's "I'm home"
 * routine — turn lights on, optionally unmute, optionally bring up media —
 * and the inverse for leaving.
 *
 * Composite — calls back into the same CompositeToolExecutor via lambda
 * to avoid a Hilt cycle.
 */
class PresenceTool(
    private val executor: () -> ToolExecutor
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "arrive_home",
            description = "User is arriving home: turn on the lights and unmute audio.",
            parameters = mapOf(
                "include_lights" to ToolParameter("boolean", "Default true", required = false),
                "include_volume" to ToolParameter("boolean", "Default true", required = false)
            )
        ),
        ToolSchema(
            name = "leave_home",
            description = "User is leaving: turn off all lights and pause media.",
            parameters = mapOf(
                "include_lights" to ToolParameter("boolean", "Default true", required = false),
                "include_media" to ToolParameter("boolean", "Default true", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        return when (call.name) {
            "arrive_home" -> arriveHome(call)
            "leave_home" -> leaveHome(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    }

    private suspend fun arriveHome(call: ToolCall): ToolResult {
        val includeLights = call.arguments["include_lights"] as? Boolean ?: true
        val includeVolume = call.arguments["include_volume"] as? Boolean ?: true
        val json = ParallelSubTools.runParallel(
            inner = executor(),
            idPrefix = "presence",
            logTag = "arrive_home",
            subs = listOf(
                ParallelSubTools.Sub(
                    includeLights, "lights_on", "execute_command",
                    arguments = mapOf("device_type" to "light", "action" to "turn_on"),
                    render = ParallelSubTools.RenderKind.Success
                ),
                ParallelSubTools.Sub(
                    includeVolume, "volume_50", "set_volume",
                    arguments = mapOf("level" to 50.0),
                    render = ParallelSubTools.RenderKind.Success
                )
            )
        )
        return ToolResult(call.id, true, json)
    }

    private suspend fun leaveHome(call: ToolCall): ToolResult {
        val includeLights = call.arguments["include_lights"] as? Boolean ?: true
        val includeMedia = call.arguments["include_media"] as? Boolean ?: true
        val json = ParallelSubTools.runParallel(
            inner = executor(),
            idPrefix = "presence",
            logTag = "leave_home",
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
                )
            )
        )
        return ToolResult(call.id, true, json)
    }
}
