package com.opensmarthome.speaker.assistant.provider.embedded

import com.opensmarthome.speaker.assistant.model.AssistantMessage
import com.opensmarthome.speaker.assistant.model.AssistantSession
import com.opensmarthome.speaker.assistant.model.ToolCallRequest
import com.opensmarthome.speaker.assistant.provider.AssistantProvider
import com.opensmarthome.speaker.assistant.provider.ProviderCapabilities
import com.opensmarthome.speaker.tool.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class EmbeddedLlmProvider(
    private val bridge: LlamaCppBridge,
    private val config: EmbeddedLlmConfig
) : AssistantProvider {

    override val id: String = "embedded_llm"
    override val displayName: String = "On-Device LLM"
    override val capabilities = ProviderCapabilities(
        supportsStreaming = true,
        supportsTools = true,
        maxContextTokens = config.contextSize,
        modelName = File(config.modelPath).nameWithoutExtension
    )

    override suspend fun startSession(config: Map<String, String>): AssistantSession {
        if (!bridge.isModelLoaded()) {
            withContext(Dispatchers.IO) {
                bridge.loadModel(
                    this@EmbeddedLlmProvider.config.modelPath,
                    this@EmbeddedLlmProvider.config.contextSize,
                    this@EmbeddedLlmProvider.config.threads,
                    this@EmbeddedLlmProvider.config.gpuLayers
                )
            }
        }
        return AssistantSession(providerId = id)
    }

    override suspend fun endSession(session: AssistantSession) {
        // Keep model loaded for faster subsequent sessions
    }

    override suspend fun send(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(messages, tools)
        val response = bridge.generate(prompt, config.maxTokens, config.temperature)
        parseResponse(response)
    }

    override fun sendStreaming(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): Flow<AssistantMessage.Delta> = flow {
        val prompt = buildPrompt(messages, tools)
        val fullResponse = StringBuilder()

        bridge.generateStreaming(prompt, config.maxTokens, config.temperature) { token ->
            fullResponse.append(token)
            // Emit token but can't from inside callback, so collect after
            true
        }

        // For streaming, re-generate with token-by-token emission
        val tokenChannel = Channel<String>(Channel.BUFFERED)
        val result = StringBuilder()

        bridge.generateStreaming(prompt, config.maxTokens, config.temperature) { token ->
            result.append(token)
            tokenChannel.trySend(token)
            true
        }
        tokenChannel.close()

        for (token in tokenChannel) {
            emit(AssistantMessage.Delta(contentDelta = token))
        }
        emit(AssistantMessage.Delta(finishReason = "stop"))
    }.flowOn(Dispatchers.IO)

    override suspend fun isAvailable(): Boolean {
        return bridge.isModelLoaded() || File(config.modelPath).exists()
    }

    override suspend fun latencyMs(): Long = 0L // On-device, no network latency

    private fun buildPrompt(messages: List<AssistantMessage>, tools: List<ToolSchema>): String {
        val sb = StringBuilder()

        // System prompt with tool schemas
        sb.append("<start_of_turn>user\n")
        sb.append(config.systemPrompt)

        if (tools.isNotEmpty()) {
            sb.append("\n\nAvailable tools:\n")
            for (tool in tools) {
                sb.append("- ${tool.name}: ${tool.description}\n")
                sb.append("  Parameters: ${tool.parameters.entries.joinToString { "${it.key}: ${it.value.description}" }}\n")
            }
            sb.append("\nTo call a tool, respond with JSON: {\"tool\": \"name\", \"arguments\": {...}}\n")
        }
        sb.append("<end_of_turn>\n")

        // Conversation history
        for (msg in messages) {
            when (msg) {
                is AssistantMessage.User -> {
                    sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                }
                is AssistantMessage.Assistant -> {
                    sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                }
                is AssistantMessage.System -> {
                    sb.append("<start_of_turn>user\n[System: ${msg.content}]<end_of_turn>\n")
                }
                is AssistantMessage.ToolCallResult -> {
                    sb.append("<start_of_turn>user\n[Tool Result: ${msg.result}]<end_of_turn>\n")
                }
                is AssistantMessage.Delta -> {}
            }
        }

        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun parseResponse(response: String): AssistantMessage {
        val trimmed = response.trim()

        // Try to extract tool call JSON
        val toolCallRegex = """\{"tool"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*(\{[^}]*\})\}""".toRegex()
        val match = toolCallRegex.find(trimmed)

        return if (match != null) {
            val toolName = match.groupValues[1]
            val toolArgs = match.groupValues[2]
            AssistantMessage.Assistant(
                content = trimmed,
                toolCalls = listOf(
                    ToolCallRequest(
                        id = "call_${java.lang.System.currentTimeMillis()}",
                        name = toolName,
                        arguments = toolArgs
                    )
                )
            )
        } else {
            AssistantMessage.Assistant(content = trimmed)
        }
    }
}
