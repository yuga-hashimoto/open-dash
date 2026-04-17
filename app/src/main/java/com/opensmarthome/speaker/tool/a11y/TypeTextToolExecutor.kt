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
 * Sends `text` to the currently focused input field via the
 * [com.opensmarthome.speaker.a11y.OpenSmartSpeakerA11yService.typeIntoFocused]
 * helper, which attempts `ACTION_SET_TEXT` first and falls back to clipboard
 * + `ACTION_PASTE` when set-text is rejected.
 *
 * Tool name: `type_text`. Requires the OpenSmartSpeaker Accessibility
 * Service to be enabled and an EditText (or similar input node) to be
 * focused on the current screen.
 */
@Singleton
class TypeTextToolExecutor @Inject constructor(
    private val holder: A11yServiceHolder
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = TOOL_NAME,
            description = "Type text into the currently focused input field. " +
                "Uses AccessibilityNodeInfo.ACTION_SET_TEXT, falling back to " +
                "clipboard paste if SET_TEXT is rejected. Requires the " +
                "OpenSmartSpeaker Accessibility Service to be enabled.",
            parameters = mapOf(
                "text" to ToolParameter(
                    type = "string",
                    description = "Text to type into the focused input field.",
                    required = true
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != TOOL_NAME) {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val text = (call.arguments["text"] as? String).orEmpty()
        if (text.isEmpty()) {
            return ToolResult(
                call.id, false, "",
                "type_text requires a non-empty 'text' argument."
            )
        }
        return try {
            val service = holder.serviceRef
                ?: return ToolResult(
                    call.id, false, "",
                    "The accessibility service isn't enabled. Ask the user to " +
                        "grant it in Settings → Accessibility → OpenSmartSpeaker."
                )
            val ok = service.typeIntoFocused(text)
            if (ok) {
                ToolResult(call.id, true, "Typed ${text.length} characters.")
            } else {
                ToolResult(
                    call.id, false, "",
                    "No input field is focused, or both set-text and paste were rejected."
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "type_text failed")
            ToolResult(call.id, false, "", e.message ?: "type_text error")
        }
    }

    companion object {
        const val TOOL_NAME: String = "type_text"
    }
}
