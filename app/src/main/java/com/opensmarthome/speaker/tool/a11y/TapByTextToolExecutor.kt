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
 * Finds a clickable node in the current foreground window whose text or
 * contentDescription contains the provided `text` query, then dispatches a
 * tap gesture at its on-screen centre via
 * [com.opensmarthome.speaker.a11y.OpenSmartSpeakerA11yService.performTapOnNode].
 *
 * Tool name: `tap_by_text`. Requires the OpenSmartSpeaker Accessibility
 * Service to be enabled.
 */
@Singleton
class TapByTextToolExecutor @Inject constructor(
    private val holder: A11yServiceHolder
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = TOOL_NAME,
            description = "Tap on an on-screen element whose visible text or " +
                "accessibility label contains the given query (case-insensitive). " +
                "Requires the OpenSmartSpeaker Accessibility Service to be enabled.",
            parameters = mapOf(
                "text" to ToolParameter(
                    type = "string",
                    description = "Text or label to match against clickable nodes.",
                    required = true
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != TOOL_NAME) {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val query = (call.arguments["text"] as? String)?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(
                call.id, false, "",
                "tap_by_text requires a non-empty 'text' argument."
            )
        }
        return try {
            val service = holder.serviceRef
                ?: return ToolResult(
                    call.id, false, "",
                    "The accessibility service isn't enabled. Ask the user to " +
                        "grant it in Settings → Accessibility → OpenSmartSpeaker."
                )
            val node = service.findNodeByText(query)
                ?: return ToolResult(
                    call.id, false, "",
                    "No clickable element matches \"$query\" on the current screen."
                )
            val ok = try {
                service.performTapOnNode(node)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
            if (ok) {
                ToolResult(call.id, true, "Tapped \"$query\".")
            } else {
                ToolResult(
                    call.id, false, "",
                    "Found \"$query\" but the tap gesture was rejected by the system."
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "tap_by_text failed")
            ToolResult(call.id, false, "", e.message ?: "tap_by_text error")
        }
    }

    companion object {
        const val TOOL_NAME: String = "tap_by_text"
    }
}
