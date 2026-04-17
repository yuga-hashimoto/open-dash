package com.opensmarthome.speaker.tool.a11y

import com.opensmarthome.speaker.a11y.A11yServiceHolder
import com.opensmarthome.speaker.tool.ToolCall
import com.opensmarthome.speaker.tool.ToolExecutor
import com.opensmarthome.speaker.tool.ToolParameter
import com.opensmarthome.speaker.tool.ToolResult
import com.opensmarthome.speaker.tool.ToolSchema
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrolls the foreground window in one of four directions by dispatching a
 * swipe gesture across the current window centre via
 * [com.opensmarthome.speaker.a11y.OpenSmartSpeakerA11yService.performSwipe].
 *
 * Tool name: `scroll_screen`. Requires the OpenSmartSpeaker Accessibility
 * Service to be enabled.
 */
@Singleton
class ScrollScreenToolExecutor @Inject constructor(
    private val holder: A11yServiceHolder
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = TOOL_NAME,
            description = "Scroll the current foreground window in a direction. " +
                "Direction values: up, down, left, right. Requires the " +
                "OpenSmartSpeaker Accessibility Service to be enabled.",
            parameters = mapOf(
                "direction" to ToolParameter(
                    type = "string",
                    description = "Swipe direction.",
                    required = true,
                    enum = SUPPORTED_DIRECTIONS
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != TOOL_NAME) {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val direction = (call.arguments["direction"] as? String)?.trim()?.lowercase().orEmpty()
        if (direction !in SUPPORTED_DIRECTIONS) {
            return ToolResult(
                call.id, false, "",
                "scroll_screen direction must be one of ${SUPPORTED_DIRECTIONS.joinToString()}. " +
                    "Got: \"$direction\"."
            )
        }
        return try {
            val service = holder.serviceRef
                ?: return ToolResult(
                    call.id, false, "",
                    "The accessibility service isn't enabled. Ask the user to " +
                        "grant it in Settings → Accessibility → OpenSmartSpeaker."
                )
            val ok = service.performSwipe(direction)
            if (ok) {
                ToolResult(call.id, true, "Scrolled $direction.")
            } else {
                ToolResult(
                    call.id, false, "",
                    "Scroll gesture was rejected by the system."
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "scroll_screen failed")
            ToolResult(call.id, false, "", e.message ?: "scroll_screen error")
        }
    }

    companion object {
        const val TOOL_NAME: String = "scroll_screen"
        val SUPPORTED_DIRECTIONS: List<String> = listOf("up", "down", "left", "right")
    }
}
