package com.opendash.app.assistant.provider.api

/**
 * Static catalog of OpenAI-compatible / Anthropic-native presets shown in
 * the "Add Provider" flow. Everything here except "anthropic" is
 * registered as an OpenAiCompatibleProvider instance by ProviderManager;
 * "anthropic" is registered as a native AnthropicProvider (see §4 of the
 * design spec — /v1/messages request/response shape differs from OpenAI).
 */
object ApiProviderCatalog {

    data class Preset(
        val id: String,
        val displayName: String,
        val defaultBaseUrl: String,
        val requiresApiKey: Boolean,
        val authStyle: String
    )

    val presets: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com", true, "bearer"),
        Preset("anthropic", "Anthropic", "https://api.anthropic.com", true, "anthropic"),
        Preset("groq", "Groq", "https://api.groq.com/openai", true, "bearer"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api", true, "bearer"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com", true, "bearer"),
        Preset("together", "Together AI", "https://api.together.xyz", true, "bearer"),
        Preset("mistral", "Mistral", "https://api.mistral.ai", true, "bearer"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai", true, "bearer"),
        Preset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference", true, "bearer"),
        Preset("moonshot", "Moonshot AI (Kimi)", "https://api.moonshot.ai", true, "bearer"),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com", true, "bearer"),
        Preset("xai", "xAI (Grok)", "https://api.x.ai", true, "bearer"),
        Preset("ollama", "Ollama (local)", "http://localhost:11434", false, "none"),
        Preset("lmstudio", "LM Studio (local)", "http://localhost:1234", false, "none"),
        Preset("custom", "Custom", "", false, "bearer")
    )

    fun find(id: String): Preset? = presets.find { it.id == id }
}
