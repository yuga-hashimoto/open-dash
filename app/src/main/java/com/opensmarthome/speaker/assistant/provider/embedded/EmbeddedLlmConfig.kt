package com.opensmarthome.speaker.assistant.provider.embedded

data class EmbeddedLlmConfig(
    val modelPath: String = "",
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val systemPrompt: String = "You are a helpful smart home assistant. When asked to control devices, use the provided tools."
)
