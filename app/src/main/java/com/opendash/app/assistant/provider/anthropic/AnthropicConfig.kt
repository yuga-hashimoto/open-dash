package com.opendash.app.assistant.provider.anthropic

data class AnthropicConfig(
    val baseUrl: String = "https://api.anthropic.com",
    val apiKey: String = "",
    val model: String = "claude-sonnet-5",
    val maxTokens: Int = 4096,
    val systemPrompt: String = "",
    val anthropicVersion: String = "2023-06-01"
)
