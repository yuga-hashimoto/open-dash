package com.opendash.app.assistant.provider

import android.content.Context
import com.opendash.app.assistant.provider.anthropic.AnthropicProvider
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmProvider
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.assistant.skills.SkillRegistry
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.device.DeviceManager
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderManagerTest {

    private lateinit var context: Context
    private lateinit var router: ConversationRouter
    private lateinit var preferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var apiProviderConfigStore: ApiProviderConfigStore
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        router = mockk(relaxed = true)
        preferences = mockk()
        securePreferences = mockk(relaxed = true)
        apiProviderConfigStore = mockk()

        every { preferences.observe(PreferenceKeys.CUSTOM_SYSTEM_PROMPT) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.OPENCLAW_GATEWAY_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.ASSISTANT_MODE) } returns flowOf(PreferenceKeys.MODE_API)
        every { preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID) } returns flowOf(null)
        coEvery { apiProviderConfigStore.list() } returns emptyList()
        every { router.availableProviders } returns MutableStateFlow(emptyList())
    }

    private fun manager() = ProviderManager(
        context = context,
        router = router,
        preferences = preferences,
        securePreferences = securePreferences,
        client = client,
        moshi = moshi,
        skillRegistry = mockk(relaxed = true),
        deviceManager = mockk(relaxed = true),
        apiProviderConfigStore = apiProviderConfigStore
    )

    @Test
    fun `api mode registers configured providers and skips embedded model`() = runTest {
        val config = ApiProviderConfig(
            id = "cfg-1",
            presetId = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com",
            modelId = "gpt-5.5",
            authStyle = "bearer",
            createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-1") } returns "sk-test"

        manager().initialize()
        advanceUntilIdle()

        // ProviderManager.initialize() dispatches onto its own internal
        // Dispatchers.IO-backed CoroutineScope (matching production, where
        // this must run off the caller's thread) rather than this test's
        // TestScope, so advanceUntilIdle() alone cannot guarantee the work
        // has completed. coVerify's timeout polls for the real background
        // coroutine to finish instead of racing it.
        coVerify(timeout = 2_000) { router.registerProvider(match { it.id == "api_cfg-1" && it.displayName == "OpenAI" }) }
    }

    @Test
    fun `api mode does not attempt to list local models`() = runTest {
        // ModelManager.listAvailableModels() touches the filesystem via the
        // real Context; api mode must never call registerEmbeddedLlm's path,
        // which we verify indirectly: no provider named "openai_compatible"
        // (the embedded-mode default id) is ever registered.
        manager().initialize()
        advanceUntilIdle()

        coVerify(exactly = 0) { router.registerProvider(match { it.id == "openai_compatible" }) }
    }

    @Test
    fun `api mode with anthropic authStyle registers AnthropicProvider`() = runTest {
        val config = ApiProviderConfig(
            id = "cfg-anthropic",
            presetId = "anthropic",
            displayName = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            modelId = "claude-sonnet-5",
            authStyle = "anthropic",
            createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-anthropic") } returns "sk-ant-test"

        manager().initialize()
        advanceUntilIdle()

        coVerify(timeout = 2_000) {
            router.registerProvider(
                match { it is AnthropicProvider && it.id == "api_cfg-anthropic" }
            )
        }
    }

    @Test
    fun `migrates legacy local llm setting into api provider config and switches mode`() = runTest {
        coEvery { apiProviderConfigStore.list() } returns emptyList()
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL) } returns flowOf("http://localhost:8080")
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL) } returns flowOf("gemma-4-e2b")
        every { securePreferences.getString(SecurePreferences.KEY_LOCAL_LLM_API_KEY) } returns "legacy-key"
        coEvery { apiProviderConfigStore.add(any(), any()) } returns Unit
        coEvery { preferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API) } returns Unit

        manager().initialize()
        advanceUntilIdle()

        coVerify(timeout = 2_000) {
            apiProviderConfigStore.add(
                match {
                    it.presetId == "custom" &&
                        it.baseUrl == "http://localhost:8080" &&
                        it.modelId == "gemma-4-e2b" &&
                        it.authStyle == "bearer"
                },
                "legacy-key"
            )
        }
        coVerify(timeout = 2_000) { preferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API) }
    }

    @Test
    fun `restores previously active provider id after registering`() = runTest {
        val config = ApiProviderConfig(
            id = "cfg-1", presetId = "openai", displayName = "OpenAI",
            baseUrl = "https://api.openai.com", modelId = "gpt-5.5",
            authStyle = "bearer", createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-1") } returns "sk-test"
        every { preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID) } returns flowOf("api_cfg-1")
        every { router.availableProviders } returns MutableStateFlow(
            listOf(
                OpenAiCompatibleProviderFakeId("api_cfg-1")
            )
        )

        manager().initialize()
        advanceUntilIdle()

        coVerify(timeout = 2_000) { router.selectProvider("api_cfg-1") }
    }

    private fun OpenAiCompatibleProviderFakeId(providerId: String): AssistantProvider {
        val fake = mockk<AssistantProvider>(relaxed = true)
        every { fake.id } returns providerId
        return fake
    }

    @Test
    fun `switchEmbeddedModel returns false when no embedded provider is registered`() = runTest {
        every { router.availableProviders } returns MutableStateFlow(
            listOf(OpenAiCompatibleProviderFakeId("api_cfg-1"))
        )

        val result = manager().switchEmbeddedModel("/models/other.task")

        assertThat(result).isFalse()
    }

    @Test
    fun `switchEmbeddedModel returns false when no providers are registered at all`() = runTest {
        every { router.availableProviders } returns MutableStateFlow(emptyList())

        val result = manager().switchEmbeddedModel("/models/other.task")

        assertThat(result).isFalse()
    }

    @Test
    fun `switchEmbeddedModel delegates to the registered EmbeddedLlmProvider and returns its result`() = runTest {
        val embedded = mockk<EmbeddedLlmProvider>(relaxed = true)
        coEvery { embedded.switchModel("/models/other.task") } returns true
        every { router.availableProviders } returns MutableStateFlow(listOf(embedded))
        coEvery { preferences.set(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH, "/models/other.task") } returns Unit

        val result = manager().switchEmbeddedModel("/models/other.task")

        assertThat(result).isTrue()
        coVerify { embedded.switchModel("/models/other.task") }
    }

    @Test
    fun `switchEmbeddedModel persists the new path on success so it survives a restart`() = runTest {
        val embedded = mockk<EmbeddedLlmProvider>(relaxed = true)
        coEvery { embedded.switchModel("/models/other.task") } returns true
        every { router.availableProviders } returns MutableStateFlow(listOf(embedded))
        coEvery { preferences.set(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH, "/models/other.task") } returns Unit

        manager().switchEmbeddedModel("/models/other.task")

        coVerify { preferences.set(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH, "/models/other.task") }
    }

    @Test
    fun `switchEmbeddedModel does not persist anything when the swap fails`() = runTest {
        val embedded = mockk<EmbeddedLlmProvider>(relaxed = true)
        coEvery { embedded.switchModel(any()) } returns false
        every { router.availableProviders } returns MutableStateFlow(listOf(embedded))

        manager().switchEmbeddedModel("/models/corrupt.task")

        coVerify(exactly = 0) { preferences.set(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH, any()) }
    }

    @Test
    fun `switchEmbeddedModel surfaces a failed swap`() = runTest {
        val embedded = mockk<EmbeddedLlmProvider>(relaxed = true)
        coEvery { embedded.switchModel(any()) } returns false
        every { router.availableProviders } returns MutableStateFlow(listOf(embedded))

        val result = manager().switchEmbeddedModel("/models/corrupt.task")

        assertThat(result).isFalse()
    }
}
