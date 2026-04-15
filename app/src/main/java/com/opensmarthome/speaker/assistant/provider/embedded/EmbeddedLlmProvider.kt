package com.opensmarthome.speaker.assistant.provider.embedded

import com.opensmarthome.speaker.assistant.model.AssistantMessage
import com.opensmarthome.speaker.assistant.model.AssistantSession
import com.opensmarthome.speaker.assistant.model.ToolCallRequest
import com.opensmarthome.speaker.assistant.provider.AssistantProvider
import com.opensmarthome.speaker.assistant.provider.ProviderCapabilities
import com.opensmarthome.speaker.tool.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class EmbeddedLlmProvider(
    private val config: EmbeddedLlmConfig
) : AssistantProvider {

    override val id: String = "embedded_llm"
    override val displayName: String = "On-Device LLM"
    override val capabilities = ProviderCapabilities(
        supportsStreaming = false,
        supportsTools = true,
        maxContextTokens = config.contextSize,
        modelName = File(config.modelPath).nameWithoutExtension
    )

    private val bridge = LlamaCppBridge()

    override suspend fun startSession(config: Map<String, String>): AssistantSession {
        if (!bridge.isModelLoaded()) {
            withContext(Dispatchers.IO) {
                val loaded = bridge.loadModel(
                    this@EmbeddedLlmProvider.config.modelPath,
                    this@EmbeddedLlmProvider.config.contextSize,
                    this@EmbeddedLlmProvider.config.threads,
                    this@EmbeddedLlmProvider.config.gpuLayers
                )
                if (!loaded) {
                    throw IllegalStateException("Failed to load model: ${this@EmbeddedLlmProvider.config.modelPath}")
                }
                Timber.d("Model loaded via llama.cpp")
            }
        }
        return AssistantSession(providerId = id)
    }

    override suspend fun endSession(session: AssistantSession) {}

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
        val response = bridge.generate(prompt, config.maxTokens, config.temperature)
        // Emit word by word for visual feedback
        for (word in response.split(" ")) {
            emit(AssistantMessage.Delta(contentDelta = "$word "))
        }
        emit(AssistantMessage.Delta(finishReason = "stop"))
    }.flowOn(Dispatchers.IO)

    override suspend fun isAvailable(): Boolean {
        return bridge.isModelLoaded() || File(config.modelPath).exists()
    }

    override suspend fun latencyMs(): Long = 0L

    fun unload() {
        bridge.unload()
    }

    private fun buildPrompt(messages: List<AssistantMessage>, tools: List<ToolSchema>): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append(config.systemPrompt)

        if (tools.isNotEmpty()) {
            sb.append("\n\nAvailable tools:\n")
            for (tool in tools) {
                sb.append("- ${tool.name}: ${tool.description}\n")
            }
            sb.append("\nTo call a tool, respond with JSON: {\"tool\": \"name\", \"arguments\": {...}}\n")
        }
        sb.append("<end_of_turn>\n")

        for (msg in messages) {
            when (msg) {
                is AssistantMessage.User -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                is AssistantMessage.Assistant -> sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                is AssistantMessage.System -> sb.append("<start_of_turn>user\n[System: ${msg.content}]<end_of_turn>\n")
                is AssistantMessage.ToolCallResult -> sb.append("<start_of_turn>user\n[Tool Result: ${msg.result}]<end_of_turn>\n")
                is AssistantMessage.Delta -> {}
            }
        }

        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun parseResponse(response: String): AssistantMessage {
        val trimmed = response.trim()
        val toolCallRegex = """\{"tool"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*(\{[^}]*\})\}""".toRegex()
        val match = toolCallRegex.find(trimmed)

        return if (match != null) {
            AssistantMessage.Assistant(
                content = trimmed,
                toolCalls = listOf(
                    ToolCallRequest(
                        id = "call_${java.lang.System.currentTimeMillis()}",
                        name = match.groupValues[1],
                        arguments = match.groupValues[2]
                    )
                )
            )
        } else {
            AssistantMessage.Assistant(content = trimmed)
        }
    }
}
