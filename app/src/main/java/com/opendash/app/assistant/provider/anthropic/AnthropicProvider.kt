package com.opendash.app.assistant.provider.anthropic

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.AssistantSession
import com.opendash.app.assistant.provider.AssistantProvider
import com.opendash.app.assistant.provider.ProviderCapabilities
import com.opendash.app.tool.ToolSchema
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AnthropicProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val config: AnthropicConfig,
    override val id: String = "anthropic",
    override val displayName: String = "Anthropic"
) : AssistantProvider {

    override val capabilities = ProviderCapabilities(
        supportsStreaming = true,
        supportsTools = true,
        maxContextTokens = 200_000,
        modelName = config.model
    )

    private val parser = AnthropicStreamParser(moshi)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun startSession(config: Map<String, String>): AssistantSession =
        AssistantSession(providerId = id)

    override suspend fun endSession(session: AssistantSession) {
        // Stateless REST - nothing to clean up
    }

    override suspend fun send(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): AssistantMessage = suspendCancellableCoroutine { cont ->
        val body = buildRequestBody(messages, tools, stream = false)
        val request = buildHttpRequest(body)

        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        try {
            val response = call.execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                cont.resumeWithException(RuntimeException("HTTP ${response.code}: $responseBody"))
                return@suspendCancellableCoroutine
            }
            cont.resume(parser.parseFullResponse(responseBody))
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    override fun sendStreaming(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): Flow<AssistantMessage.Delta> = flow {
        val body = buildRequestBody(messages, tools, stream = true)
        val request = buildHttpRequest(body)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: ${response.body?.string()}")
        }

        val responseBody = response.body ?: throw RuntimeException("Empty response body")
        val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
        reader.use {
            val lines = reader.lineSequence().iterator()
            while (lines.hasNext()) {
                val parsed = parser.parseLine(lines.next()) ?: continue
                emit(parsed)
                if (parsed.finishReason != null) break
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${config.baseUrl}/v1/models")
                .addHeader("x-api-key", config.apiKey)
                .addHeader("anthropic-version", config.anthropicVersion)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            Timber.d(e, "Anthropic provider unavailable: ${config.baseUrl}")
            false
        }
    }

    override suspend fun latencyMs(): Long {
        val start = java.lang.System.currentTimeMillis()
        isAvailable()
        return java.lang.System.currentTimeMillis() - start
    }

    private fun buildHttpRequest(body: String): Request {
        return Request.Builder()
            .url("${config.baseUrl}/v1/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", config.anthropicVersion)
            .post(body.toRequestBody(jsonMediaType))
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRequestBody(
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>,
        stream: Boolean
    ): String {
        val msgList = messages.mapNotNull { msg ->
            when (msg) {
                is AssistantMessage.User -> mapOf("role" to "user", "content" to msg.content)
                is AssistantMessage.Assistant -> mapOf(
                    "role" to "assistant",
                    "content" to assistantContent(msg)
                )
                is AssistantMessage.ToolCallResult -> mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "tool_result",
                            "tool_use_id" to msg.callId,
                            "content" to msg.result,
                            "is_error" to msg.isError
                        )
                    )
                )
                is AssistantMessage.System -> null
                is AssistantMessage.Delta -> null
            }
        }

        val systemText = messages.filterIsInstance<AssistantMessage.System>()
            .joinToString("\n") { it.content }
            .ifBlank { config.systemPrompt }

        val payload = mutableMapOf<String, Any>(
            "model" to config.model,
            "max_tokens" to config.maxTokens,
            "messages" to msgList,
            "stream" to stream
        )
        if (systemText.isNotBlank()) payload["system"] = systemText

        if (tools.isNotEmpty()) {
            payload["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to mapOf(
                        "type" to "object",
                        "properties" to tool.parameters.mapValues { (_, param) ->
                            mutableMapOf<String, Any>(
                                "type" to param.type,
                                "description" to param.description
                            ).apply {
                                param.enum?.let { put("enum", it) }
                            }
                        },
                        "required" to tool.parameters.filter { it.value.required }.keys.toList()
                    )
                )
            }
        }

        return moshi.adapter(Map::class.java).toJson(payload as Map<Any?, Any?>) ?: "{}"
    }

    private fun assistantContent(msg: AssistantMessage.Assistant): Any {
        if (msg.toolCalls.isEmpty()) return msg.content

        return buildList {
            if (msg.content.isNotBlank()) add(mapOf("type" to "text", "text" to msg.content))
            msg.toolCalls.forEach { toolCall ->
                add(
                    mapOf(
                        "type" to "tool_use",
                        "id" to toolCall.id,
                        "name" to toolCall.name,
                        "input" to parseToolCallArguments(toolCall.arguments)
                    )
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolCallArguments(arguments: String): Map<String, Any?> {
        return try {
            moshi.adapter(Map::class.java).fromJson(arguments) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
