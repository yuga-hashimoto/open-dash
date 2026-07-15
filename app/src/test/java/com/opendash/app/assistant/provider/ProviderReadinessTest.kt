package com.opendash.app.assistant.provider

import android.content.Context
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ProviderManager.initialize() runs on a real Dispatchers.IO scope (production
 * contract), so these tests use [runBlocking] + wall-clock [withTimeout]
 * rather than runTest virtual time.
 */
class ProviderReadinessTest {

    private lateinit var context: Context
    private lateinit var router: ConversationRouter
    private lateinit var preferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var apiProviderConfigStore: ApiProviderConfigStore
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient()
    private val providersFlow = MutableStateFlow<List<AssistantProvider>>(emptyList())

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
        every { router.availableProviders } returns providersFlow
        coEvery { router.registerProvider(any()) } coAnswers {
            val provider = firstArg<AssistantProvider>()
            providersFlow.value = providersFlow.value + provider
        }
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
    fun `starts in Starting readiness`() {
        assertThat(manager().readiness.value).isEqualTo(ProviderReadiness.Starting)
    }

    @Test
    fun `cold boot with no providers becomes Degraded after awaitReady`() = runBlocking {
        val m = manager()
        m.initialize()
        withTimeout(3_000) { m.awaitReady() }

        assertThat(m.readiness.value).isInstanceOf(ProviderReadiness.Degraded::class.java)
    }

    @Test
    fun `api mode with configured provider becomes Ready`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        val config = ApiProviderConfig(
            id = "cfg-1",
            presetId = "openai",
            displayName = "OpenAI",
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelId = "gpt-5.5",
            authStyle = "bearer",
            createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-1") } returns "sk-test"

        try {
            val m = manager()
            m.initialize()
            withTimeout(3_000) { m.awaitReady() }

            assertThat(m.readiness.value).isEqualTo(ProviderReadiness.Ready)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `initialize is idempotent — second call does not re-register providers`() = runBlocking {
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

        val m = manager()
        m.initialize()
        withTimeout(3_000) { m.awaitReady() }
        m.initialize()
        withTimeout(3_000) { m.awaitReady() }

        coVerify(exactly = 1) { router.registerProvider(match { it.id == "api_cfg-1" }) }
    }
}
