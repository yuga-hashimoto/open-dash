package com.opendash.app.assistant.provider

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.assistant.skills.SkillRegistry
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.device.DeviceManager
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
}
