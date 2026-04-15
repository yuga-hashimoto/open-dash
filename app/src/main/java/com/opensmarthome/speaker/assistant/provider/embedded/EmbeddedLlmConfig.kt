package com.opensmarthome.speaker.assistant.provider.embedded

data class EmbeddedLlmConfig(
    val modelPath: String = "",
    val contextSize: Int = 1024,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
    val gpuLayers: Int = 0,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 128,
    val systemPrompt: String = "You are a smart speaker. Answer briefly."
)
