package com.opendash.app.tool.translate

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import com.opendash.app.tool.escapeJson
import timber.log.Timber

/**
 * Translates text entirely on-device via [TranslateEngine] (ML Kit in
 * production) — no external API, no per-request network call after
 * the language-pair model is downloaded once. Fills a gap this app
 * had zero coverage for previously.
 */
class TranslateToolExecutor(
    private val engine: TranslateEngine
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "translate_text",
            description = "Translate text from one language to another, entirely on-device. " +
                "Use ISO 639-1 two-letter codes for languages, e.g. 'en', 'ja', 'fr', 'es', 'de', 'zh'.",
            parameters = mapOf(
                "text" to ToolParameter("string", "The text to translate", required = true),
                "source_language" to ToolParameter("string", "Source language ISO 639-1 code, e.g. 'en'", required = true),
                "target_language" to ToolParameter("string", "Target language ISO 639-1 code, e.g. 'ja'", required = true)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "translate_text" -> executeTranslate(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Translate tool failed")
        ToolResult(call.id, false, "", e.message ?: "Translation failed")
    }

    private suspend fun executeTranslate(call: ToolCall): ToolResult {
        val text = (call.arguments["text"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing text")
        val sourceLanguage = call.arguments["source_language"] as? String
            ?: return ToolResult(call.id, false, "", "Missing source_language")
        val targetLanguage = call.arguments["target_language"] as? String
            ?: return ToolResult(call.id, false, "", "Missing target_language")

        return when (val result = engine.translate(text, sourceLanguage, targetLanguage)) {
            is TranslateResult.Translated -> ToolResult(
                call.id, true,
                """{"translated_text":"${result.text.escapeJson()}","source_language":"${sourceLanguage.escapeJson()}","target_language":"${targetLanguage.escapeJson()}"}"""
            )
            is TranslateResult.UnsupportedLanguage ->
                ToolResult(call.id, false, "", "Unsupported language: ${result.tag}")
            is TranslateResult.Failed ->
                ToolResult(call.id, false, "", result.reason)
        }
    }
}
