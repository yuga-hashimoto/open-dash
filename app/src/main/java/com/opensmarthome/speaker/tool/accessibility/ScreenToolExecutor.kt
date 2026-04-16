package com.opensmarthome.speaker.tool.accessibility

import com.opensmarthome.speaker.tool.ToolCall
import com.opensmarthome.speaker.tool.ToolExecutor
import com.opensmarthome.speaker.tool.ToolResult
import com.opensmarthome.speaker.tool.ToolSchema
import timber.log.Timber

class ScreenToolExecutor(
    private val screenReader: ScreenReader
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "read_screen",
            description = "Read text currently visible on the screen. Requires the OpenSmartSpeaker Accessibility Service to be enabled.",
            parameters = emptyMap()
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        return try {
            when (call.name) {
                "read_screen" -> executeRead(call)
                else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Screen tool failed")
            ToolResult(call.id, false, "", e.message ?: "Screen error")
        }
    }

    private fun executeRead(call: ToolCall): ToolResult {
        if (!screenReader.isReady()) {
            return ToolResult(
                call.id, false, "",
                "Accessibility service not enabled. Ask user to enable 'OpenSmartSpeaker' in Settings > Accessibility."
            )
        }

        val snapshot = screenReader.readScreen()
            ?: return ToolResult(call.id, false, "", "No active window")

        val visible = snapshot.visibleTexts.joinToString(",") { """"${it.escapeJson()}"""" }
        val clickable = snapshot.clickableLabels.joinToString(",") { """"${it.escapeJson()}"""" }
        val data = """{"package":"${snapshot.packageName.escapeJson()}","visible_texts":[$visible],"clickable":[$clickable]}"""
        return ToolResult(call.id, true, data)
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
