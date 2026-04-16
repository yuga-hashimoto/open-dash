package com.opensmarthome.speaker.assistant.provider.embedded

import com.opensmarthome.speaker.assistant.model.ToolCallRequest
import timber.log.Timber

/**
 * Parses LLM output to extract tool calls and plain text.
 *
 * Supports multiple formats (stolen from off-grid-mobile-ai multi-format pattern):
 * - JSON new: {"tool_call": {"name": "...", "arguments": {...}}}
 * - JSON legacy: {"tool": "...", "arguments": {...}}
 * - XML wrapper: <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 * - Gemma 4 style: <|tool_call>{"name": "...", "arguments": {...}}<tool_call|>
 *
 * XML tokens are stripped out of the visible text output (Gemma 4 pattern).
 */
class ToolCallParser {

    data class ParseResult(
        val text: String,
        val toolCalls: List<ToolCallRequest>
    )

    fun parse(response: String): ParseResult {
        if (response.isBlank()) return ParseResult("", emptyList())

        val toolCalls = mutableListOf<ToolCallRequest>()

        // First: extract XML/Gemma-style tool calls (may span multiple lines)
        var cleaned = response
        for (xmlRegex in XML_REGEXES) {
            xmlRegex.findAll(cleaned).forEach { match ->
                val inner = match.groupValues[1].trim()
                tryParseJson(inner)?.let { toolCalls.add(it) }
            }
            cleaned = cleaned.replace(xmlRegex, "")
        }

        // Then: line-by-line JSON parsing on the remainder
        val textParts = mutableListOf<String>()
        for (line in cleaned.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val toolCall = tryParseToolCall(trimmed)
            if (toolCall != null) {
                toolCalls.add(toolCall)
            } else {
                textParts.add(line)
            }
        }

        return ParseResult(
            text = textParts.joinToString("\n").trim(),
            toolCalls = toolCalls
        )
    }

    private fun tryParseToolCall(line: String): ToolCallRequest? {
        if (!line.startsWith("{")) return null

        return tryParseNewFormat(line) ?: tryParseLegacyFormat(line)
    }

    /**
     * Parse a JSON blob that may be either new, legacy, or a bare {name, arguments} form.
     * Used for content extracted from XML wrappers.
     */
    private fun tryParseJson(json: String): ToolCallRequest? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null

        // Try formats in priority order
        tryParseNewFormat(trimmed)?.let { return it }
        tryParseLegacyFormat(trimmed)?.let { return it }
        // Bare {name, arguments} — used inside XML wrappers
        return tryParseBareFormat(trimmed)
    }

    /**
     * Bare format (used inside XML wrappers): {"name": "...", "arguments": {...}}
     */
    private fun tryParseBareFormat(line: String): ToolCallRequest? {
        val match = BARE_NAME_REGEX.find(line) ?: return null
        return try {
            val name = match.groupValues[1]
            val arguments = extractArguments(line, match.range.last) ?: return null
            if (name.isNotBlank()) {
                ToolCallRequest(
                    id = "call_${System.currentTimeMillis()}",
                    name = name,
                    arguments = arguments
                )
            } else null
        } catch (e: Exception) {
            Timber.d("Failed to parse bare format tool call: $line")
            null
        }
    }

    /**
     * New format: {"tool_call": {"name": "...", "arguments": {...}}}
     */
    private fun tryParseNewFormat(line: String): ToolCallRequest? {
        val match = NEW_FORMAT_REGEX.find(line) ?: return null
        return try {
            val name = match.groupValues[1]
            val arguments = extractArguments(line, match.range.last) ?: return null
            if (name.isNotBlank()) {
                ToolCallRequest(
                    id = "call_${System.currentTimeMillis()}",
                    name = name,
                    arguments = arguments
                )
            } else null
        } catch (e: Exception) {
            Timber.d("Failed to parse new format tool call: $line")
            null
        }
    }

    /**
     * Legacy format: {"tool": "...", "arguments": {...}}
     */
    private fun tryParseLegacyFormat(line: String): ToolCallRequest? {
        val match = LEGACY_FORMAT_REGEX.find(line) ?: return null
        return try {
            val name = match.groupValues[1]
            val arguments = extractArguments(line, match.range.last) ?: return null
            if (name.isNotBlank()) {
                ToolCallRequest(
                    id = "call_${System.currentTimeMillis()}",
                    name = name,
                    arguments = arguments
                )
            } else null
        } catch (e: Exception) {
            Timber.d("Failed to parse legacy format tool call: $line")
            null
        }
    }

    /**
     * Extract the "arguments" JSON object from the line, handling nested braces.
     * Returns null if no valid JSON object is found.
     */
    private fun extractArguments(line: String, searchStart: Int): String? {
        val argsMatch = ARGUMENTS_KEY_REGEX.find(line, searchStart.coerceAtLeast(0))
            ?: return null

        val braceStart = line.indexOf('{', argsMatch.range.last)
        if (braceStart == -1) return null

        var depth = 0
        for (i in braceStart until line.length) {
            when (line[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val extracted = line.substring(braceStart, i + 1)
                        // Empty object {} is valid. Otherwise must contain a quoted key.
                        return if (extracted == "{}" || extracted.contains("\"")) extracted else null
                    }
                }
            }
        }

        return null // Unbalanced braces
    }

    companion object {
        private val NEW_FORMAT_REGEX =
            """"tool_call"\s*:\s*\{\s*"name"\s*:\s*"(\w+)"""".toRegex()

        private val LEGACY_FORMAT_REGEX =
            """"tool"\s*:\s*"(\w+)"""".toRegex()

        private val BARE_NAME_REGEX =
            """"name"\s*:\s*"(\w+)"""".toRegex()

        private val ARGUMENTS_KEY_REGEX =
            """"arguments"\s*:\s*""".toRegex()

        // XML-style wrappers (content is a JSON body).
        // Using DOT_MATCHES_ALL so the inner JSON can span multiple lines.
        private val XML_REGEXES = listOf(
            // Standard: <tool_call>...</tool_call>
            """<tool_call>([\s\S]*?)</tool_call>""".toRegex(),
            // Gemma 4 style: <|tool_call>...<tool_call|>
            """<\|tool_call>([\s\S]*?)<tool_call\|>""".toRegex()
        )
    }
}
