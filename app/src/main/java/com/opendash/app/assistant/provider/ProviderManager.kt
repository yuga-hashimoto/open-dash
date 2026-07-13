package com.opendash.app.assistant.provider

import android.content.Context
import com.opendash.app.assistant.provider.anthropic.AnthropicConfig
import com.opendash.app.assistant.provider.anthropic.AnthropicProvider
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmConfig
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmProvider
import com.opendash.app.assistant.provider.embedded.HardwareProfile
import com.opendash.app.assistant.provider.embedded.ModelManager
import com.opendash.app.assistant.skills.SkillRegistry
import com.opendash.app.device.DeviceManager
import com.opendash.app.assistant.provider.openai.OpenAiCompatibleConfig
import com.opendash.app.assistant.provider.openai.OpenAiCompatibleProvider
import com.opendash.app.assistant.provider.openclaw.OpenClawConfig
import com.opendash.app.assistant.provider.openclaw.OpenClawProvider
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: ConversationRouter,
    private val preferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val skillRegistry: SkillRegistry,
    private val deviceManager: DeviceManager,
    private val apiProviderConfigStore: ApiProviderConfigStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelManager = ModelManager(context)

    fun initialize() {
        scope.launch {
            migrateLegacyLocalLlmSettingIfNeeded()
            val mode = preferences.observe(PreferenceKeys.ASSISTANT_MODE).first()
            if (mode == PreferenceKeys.MODE_API) {
                registerConfiguredApiProviders()
            } else {
                registerEmbeddedLlm()
            }
            registerOpenClawIfConfigured()
            restoreActiveProviderSelection()
        }
    }

    private suspend fun migrateLegacyLocalLlmSettingIfNeeded() {
        if (apiProviderConfigStore.list().isNotEmpty()) return
        val legacyUrl = preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL).first()
        if (legacyUrl.isNullOrBlank()) return
        val legacyModel = preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL).first() ?: "gemma-4-e2b"
        val legacyApiKey = securePreferences.getString(SecurePreferences.KEY_LOCAL_LLM_API_KEY)
        val migrated = ApiProviderConfig(
            id = java.util.UUID.randomUUID().toString(),
            presetId = "custom",
            displayName = "Migrated Local LLM",
            baseUrl = legacyUrl,
            modelId = legacyModel,
            authStyle = "bearer",
            createdAt = java.lang.System.currentTimeMillis()
        )
        apiProviderConfigStore.add(migrated, legacyApiKey)
        preferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API)
        Timber.d("Migrated legacy LOCAL_LLM_BASE_URL into ApiProviderConfig ${migrated.id}")
    }

    private suspend fun registerEmbeddedLlm() {
        try {
            val models = modelManager.listAvailableModels()
            if (models.isNotEmpty()) {
                // Prefer the model the user last picked via switchEmbeddedModel
                // (P16.6); fall back to the first model found if unset or if
                // it points at a file that's since been deleted.
                val savedPath = preferences.observe(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH).first()
                val selected = models.firstOrNull { it.path == savedPath } ?: models.first()
                val modelPath = selected.path
                // User-customized system prompt overrides the default when set
                val customPrompt = preferences.observe(PreferenceKeys.CUSTOM_SYSTEM_PROMPT).first()
                val systemPrompt = customPrompt?.takeIf { it.isNotBlank() }
                    ?: EmbeddedLlmConfig.DEFAULT_SYSTEM_PROMPT
                // Tune context size / thread count / GPU layers to device hardware
                val profile = HardwareProfile.fromContext(context)
                val config = EmbeddedLlmConfig.forHardware(
                    modelPath = modelPath,
                    profile = profile,
                    systemPrompt = systemPrompt
                )
                Timber.d("EmbeddedLlm tuned for ${profile.tier}: ctx=${config.contextSize} threads=${config.threads}")
                val provider = EmbeddedLlmProvider(
                    context = context,
                    initialConfig = config,
                    skillRegistry = skillRegistry,
                    deviceManager = deviceManager
                )
                router.registerProvider(provider)
                Timber.d("Registered EmbeddedLlmProvider with model: ${selected.name} (custom prompt: ${!customPrompt.isNullOrBlank()})")
                // Pre-warm the engine in the background so the first user
                // request doesn't pay the GPU/CPU init cost.
                scope.launch { provider.warmUp() }
            } else {
                Timber.d("No models found, EmbeddedLlmProvider not registered")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to register EmbeddedLlmProvider")
        }
    }

    private suspend fun registerConfiguredApiProviders() {
        apiProviderConfigStore.list().forEach { config ->
            try {
                val apiKey = apiProviderConfigStore.apiKeyFor(config.id)
                val routerId = "api_${config.id}"
                val provider: AssistantProvider = if (config.authStyle == "anthropic") {
                    AnthropicProvider(
                        client = client,
                        moshi = moshi,
                        config = AnthropicConfig(baseUrl = config.baseUrl, apiKey = apiKey, model = config.modelId),
                        id = routerId,
                        displayName = config.displayName
                    )
                } else {
                    OpenAiCompatibleProvider(
                        client = client,
                        moshi = moshi,
                        config = OpenAiCompatibleConfig(baseUrl = config.baseUrl, apiKey = apiKey, model = config.modelId),
                        id = routerId,
                        displayName = config.displayName
                    )
                }
                router.registerProvider(provider)
                Timber.d("Registered API provider: ${config.displayName} ($routerId)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to register API provider ${config.id}")
            }
        }
    }

    private suspend fun registerOpenClawIfConfigured() {
        try {
            val url = preferences.observe(PreferenceKeys.OPENCLAW_GATEWAY_URL).first()
            if (!url.isNullOrBlank()) {
                val provider = OpenClawProvider(
                    client = client,
                    moshi = moshi,
                    config = OpenClawConfig(gatewayUrl = url)
                )
                router.registerProvider(provider)
                Timber.d("Registered OpenClawProvider: $url")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to register OpenClawProvider")
        }
    }

    private suspend fun restoreActiveProviderSelection() {
        val activeId = preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID).first() ?: return
        if (router.availableProviders.value.any { it.id == activeId }) {
            router.selectProvider(activeId)
        }
    }

    fun getModelManager(): ModelManager = modelManager

    /**
     * Hot-swaps the registered [EmbeddedLlmProvider]'s active model (P16.6) —
     * no app relaunch, no new provider/session object; [router.availableProviders]
     * keeps pointing at the same instance the rest of the app already holds a
     * reference to (VoicePipeline via `resolveProvider()`), it just now serves
     * a different model underneath. [modelPath] should come from
     * [ModelManager.listAvailableModels] (already supports multiple models
     * coexisting on disk — nothing to change there).
     *
     * Returns false if no embedded provider is currently registered (e.g. API
     * mode is active) or if the underlying [EmbeddedLlmProvider.switchModel]
     * failed to load [modelPath] (it reverts to the previous model in that case,
     * so the app keeps working — this call just reports the swap didn't happen).
     * On success, persists [modelPath] so `registerEmbeddedLlm()` picks the
     * same model back up after the app is killed and relaunched — otherwise
     * the swap would only last until the next cold start.
     */
    suspend fun switchEmbeddedModel(modelPath: String): Boolean {
        val provider = router.availableProviders.value
            .filterIsInstance<EmbeddedLlmProvider>()
            .firstOrNull() ?: return false
        val success = provider.switchModel(modelPath)
        if (success) {
            preferences.set(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH, modelPath)
        }
        return success
    }
}
