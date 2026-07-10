package com.opendash.app.assistant.provider.api

import com.squareup.moshi.JsonClass

/**
 * Persisted configuration for one user-added API provider. `authStyle`
 * selects which AssistantProvider implementation ProviderManager
 * constructs: "bearer" -> OpenAiCompatibleProvider, "anthropic" ->
 * AnthropicProvider, "none" -> OpenAiCompatibleProvider with no auth
 * header (local servers like Ollama/LM Studio).
 */
@JsonClass(generateAdapter = true)
data class ApiProviderConfig(
    val id: String,
    val presetId: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val authStyle: String,
    val createdAt: Long
)
