package com.opendash.app.assistant.provider.openai

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.squareup.moshi.Moshi
import timber.log.Timber

// JSON-shaped map alias — the moshi adapter returns Map<String, Any?>
// for arbitrary JSON objects, and every nested lookup immediately casts
// back to this shape. Keeping a local alias cuts repetition and pushes
// the one UNCHECKED_CAST suppression to the top-level function where it
// belongs.
private typealias JsonMap = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
class OpenAiStreamParser(private val moshi: Moshi) {

    fun parseLine(line: String): AssistantMessage.Delta? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == "data: [DONE]") return null
        val json = if (trimmed.startsWith("data: ")) trimmed.removePrefix("data: ") else return null

        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap ?: return null
            val choices = (map["choices"] as? List<*>)?.firstOrNull() as? JsonMap ?: return null
            val delta = choices["delta"] as? JsonMap
            val finishReason = choices["finish_reason"] as? String

            val content = delta?.get("content") as? String ?: ""
            val toolCallDelta = parseToolCallDelta(delta)

            AssistantMessage.Delta(
                contentDelta = content,
                toolCallDelta = toolCallDelta,
                finishReason = finishReason
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse SSE line: $json")
            null
        }
    }

    fun parseFullResponse(json: String): AssistantMessage {
        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap
                ?: return AssistantMessage.Assistant(content = "")
            val choices = (map["choices"] as? List<*>)?.firstOrNull() as? JsonMap
                ?: return AssistantMessage.Assistant(content = "")
            val message = choices["message"] as? JsonMap
                ?: return AssistantMessage.Assistant(content = "")

            val content = message["content"] as? String ?: ""
            val toolCalls = parseToolCalls(message)

            AssistantMessage.Assistant(
                content = content,
                toolCalls = toolCalls
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse full response")
            AssistantMessage.Assistant(content = "")
        }
    }

    private fun parseToolCalls(message: JsonMap): List<ToolCallRequest> {
        val toolCalls = message["tool_calls"] as? List<*> ?: return emptyList()
        return toolCalls.mapNotNull { tc ->
            val call = tc as? JsonMap ?: return@mapNotNull null
            val function = call["function"] as? JsonMap ?: return@mapNotNull null
            ToolCallRequest(
                id = call["id"] as? String ?: "",
                name = function["name"] as? String ?: "",
                arguments = function["arguments"] as? String ?: "{}"
            )
        }
    }

    private fun parseToolCallDelta(delta: JsonMap?): ToolCallRequest? {
        val toolCalls = delta?.get("tool_calls") as? List<*> ?: return null
        val firstCall = toolCalls.firstOrNull() as? JsonMap ?: return null
        val function = firstCall["function"] as? JsonMap ?: return null
        return ToolCallRequest(
            id = firstCall["id"] as? String ?: "",
            name = function["name"] as? String ?: "",
            arguments = function["arguments"] as? String ?: ""
        )
    }
}
