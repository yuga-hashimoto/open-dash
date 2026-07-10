package com.opendash.app.assistant.provider.api

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiProviderConfigStoreTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val listAdapter = moshi.adapter<List<ApiProviderConfig>>(
        Types.newParameterizedType(List::class.java, ApiProviderConfig::class.java)
    )
    private lateinit var appPreferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var store: ApiProviderConfigStore
    private var storedJson: String? = null

    @BeforeEach
    fun setup() {
        appPreferences = mockk()
        securePreferences = mockk(relaxed = true)
        storedJson = null
        every { appPreferences.observe(PreferenceKeys.API_PROVIDER_CONFIGS) } answers {
            MutableStateFlow(storedJson)
        }
        coEvery { appPreferences.set(PreferenceKeys.API_PROVIDER_CONFIGS, any()) } answers {
            storedJson = secondArg()
        }
        store = ApiProviderConfigStore(appPreferences, securePreferences, moshi)
    }

    private fun sampleConfig(id: String = "cfg-1") = ApiProviderConfig(
        id = id,
        presetId = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com",
        modelId = "gpt-5.5",
        authStyle = "bearer",
        createdAt = 1L
    )

    @Test
    fun `list is empty when nothing stored`() = runTest {
        assertThat(store.list()).isEmpty()
    }

    @Test
    fun `add then list round-trips the config`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        val result = store.list()
        assertThat(result).hasSize(1)
        assertThat(result.first().id).isEqualTo("cfg-1")
        assertThat(result.first().displayName).isEqualTo("OpenAI")
    }

    @Test
    fun `add stores the api key under a per-config secure key`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        verify { securePreferences.putString("api_provider_key_cfg-1", "sk-test") }
    }

    @Test
    fun `add with blank api key does not touch secure preferences`() = runTest {
        store.add(sampleConfig(), apiKey = "")

        verify(exactly = 0) { securePreferences.putString(any(), any()) }
    }

    @Test
    fun `add twice with same id replaces the existing entry`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-1")
        store.add(sampleConfig().copy(displayName = "OpenAI Renamed"), apiKey = "sk-2")

        val result = store.list()
        assertThat(result).hasSize(1)
        assertThat(result.first().displayName).isEqualTo("OpenAI Renamed")
    }

    @Test
    fun `remove deletes the config and its secure key`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        store.remove("cfg-1")

        assertThat(store.list()).isEmpty()
        verify { securePreferences.remove("api_provider_key_cfg-1") }
    }

    @Test
    fun `apiKeyFor reads from secure preferences using the same key convention`() {
        every { securePreferences.getString("api_provider_key_cfg-1", "") } returns "sk-stored"

        assertThat(store.apiKeyFor("cfg-1")).isEqualTo("sk-stored")
    }

    @Test
    fun `list returns empty list when stored value is malformed json`() = runTest {
        storedJson = "not valid json"

        val result = store.list()

        assertThat(result).isEmpty()
    }

    @Test
    fun `observeList reactively reflects each emission from the underlying preference flow`() = runTest {
        val configJson = listAdapter.toJson(listOf(sampleConfig()))
        val preferenceFlow = MutableStateFlow<String?>(null)
        every { appPreferences.observe(PreferenceKeys.API_PROVIDER_CONFIGS) } returns preferenceFlow

        val collected = mutableListOf<List<ApiProviderConfig>>()
        val job = launch { store.observeList().toList(collected) }
        runCurrent()

        preferenceFlow.value = configJson
        runCurrent()

        preferenceFlow.value = null
        runCurrent()

        job.cancel()

        assertThat(collected).hasSize(3)
        assertThat(collected[0]).isEmpty()
        assertThat(collected[1]).hasSize(1)
        assertThat(collected[1].first().id).isEqualTo("cfg-1")
        assertThat(collected[2]).isEmpty()
    }
}
