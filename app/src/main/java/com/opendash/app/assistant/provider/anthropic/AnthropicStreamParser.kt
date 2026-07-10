package com.opendash.app.assistant.provider.anthropic

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.squareup.moshi.Moshi
import timber.log.Timber

private typealias JsonMap = Map<String, Any?>

/**
 * Parses Anthropic Messages API SSE lines and full (non-streaming)
 * responses. Unlike OpenAI's stream, every Anthropic event carries its
 * own "type" field inside the JSON payload, so there's no need to track
 * a separate SSE "event:" line.
 */
@Suppress("UNCHECKED_CAST")
class AnthropicStreamParser(private val moshi: Moshi) {

    fun parseLine(line: String): AssistantMessage.Delta? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data: ")) return null
        val json = trimmed.removePrefix("data: ")

        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap ?: return null
            when (map["type"] as? String) {
                "content_block_delta" -> parseContentBlockDelta(map)
                "content_block_start" -> parseContentBlockStart(map)
                "message_delta" -> parseMessageDelta(map)
                "error" -> AssistantMessage.Delta(finishReason = "error")
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Anthropic SSE line: $json")
            null
        }
    }

    private fun parseContentBlockDelta(map: JsonMap): AssistantMessage.Delta? {
        val delta = map["delta"] as? JsonMap ?: return null
        return when (delta["type"] as? String) {
            "text_delta" -> AssistantMessage.Delta(contentDelta = delta["text"] as? String ?: "")
            "input_json_delta" -> AssistantMessage.Delta(
                toolCallDelta = ToolCallRequest(id = "", name = "", arguments = delta["partial_json"] as? String ?: "")
            )
            else -> null
        }
    }

    private fun parseContentBlockStart(map: JsonMap): AssistantMessage.Delta? {
        val block = map["content_block"] as? JsonMap ?: return null
        if (block["type"] as? String != "tool_use") return null
        return AssistantMessage.Delta(
            toolCallDelta = ToolCallRequest(
                id = block["id"] as? String ?: "",
                name = block["name"] as? String ?: "",
                arguments = ""
            )
        )
    }

    private fun parseMessageDelta(map: JsonMap): AssistantMessage.Delta? {
        val delta = map["delta"] as? JsonMap ?: return null
        val stopReason = delta["stop_reason"] as? String ?: return null
        return AssistantMessage.Delta(finishReason = stopReason)
    }

    fun parseFullResponse(json: String): AssistantMessage {
        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap
                ?: return AssistantMessage.Assistant(content = "")
            val blocks = map["content"] as? List<*> ?: return AssistantMessage.Assistant(content = "")

            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCallRequest>()
            blocks.forEach { block ->
                val b = block as? JsonMap ?: return@forEach
                when (b["type"] as? String) {
                    "text" -> text.append(b["text"] as? String ?: "")
                    "tool_use" -> toolCalls.add(
                        ToolCallRequest(
                            id = b["id"] as? String ?: "",
                            name = b["name"] as? String ?: "",
                            arguments = moshi.adapter(Map::class.java).toJson(b["input"] as? JsonMap ?: emptyMap<String, Any?>())
                        )
                    )
                }
            }
            AssistantMessage.Assistant(content = text.toString(), toolCalls = toolCalls)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Anthropic full response")
            AssistantMessage.Assistant(content = "")
        }
    }
}
