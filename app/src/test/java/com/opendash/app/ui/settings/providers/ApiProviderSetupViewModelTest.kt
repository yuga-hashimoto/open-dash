package com.opendash.app.ui.settings.providers

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.api.ApiProviderCatalog
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.api.ModelListFetcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiProviderSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var store: ApiProviderConfigStore
    private lateinit var fetcher: ModelListFetcher
    private lateinit var viewModel: ApiProviderSetupViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        store = mockk(relaxed = true)
        fetcher = mockk()
        viewModel = ApiProviderSetupViewModel(store, fetcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting a preset prefills display name and base url`() = runTest {
        viewModel.state.test {
            assertThat(awaitItem().selectedPreset).isNull()

            viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)

            val state = awaitItem()
            assertThat(state.selectedPreset?.id).isEqualTo("openai")
            assertThat(state.displayName).isEqualTo("OpenAI")
            assertThat(state.baseUrl).isEqualTo("https://api.openai.com")
        }
    }

    @Test
    fun `fetchModels populates availableModels on success`() = runTest {
        coEvery { fetcher.fetch("https://api.openai.com", "sk-test", "bearer") } returns
            Result.success(listOf("gpt-5.5", "gpt-5.5-mini"))

        viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)
        viewModel.updateApiKey("sk-test")

        viewModel.state.test {
            awaitItem() // initial after preset+key updates settle from setup above is racy;
            viewModel.fetchModels()
            val loading = awaitItem()
            assertThat(loading.isFetchingModels).isTrue()
            val loaded = awaitItem()
            assertThat(loaded.isFetchingModels).isFalse()
            assertThat(loaded.availableModels).containsExactly("gpt-5.5", "gpt-5.5-mini")
            assertThat(loaded.modelFetchFailed).isFalse()
        }
    }

    @Test
    fun `fetchModels sets modelFetchFailed on failure so UI can fall back to free text`() = runTest {
        coEvery { fetcher.fetch(any(), any(), any()) } returns Result.failure(RuntimeException("boom"))
        viewModel.selectPreset(ApiProviderCatalog.find("ollama")!!)

        viewModel.fetchModels()
        advanceUntilIdle()

        assertThat(viewModel.state.value.modelFetchFailed).isTrue()
        assertThat(viewModel.state.value.isFetchingModels).isFalse()
    }

    @Test
    fun `save persists a config and marks state saved`() = runTest {
        viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)
        viewModel.updateApiKey("sk-test")
        viewModel.selectModel("gpt-5.5")

        viewModel.save()
        advanceUntilIdle()

        coVerify {
            store.add(
                match { it.presetId == "openai" && it.baseUrl == "https://api.openai.com" && it.modelId == "gpt-5.5" && it.authStyle == "bearer" },
                "sk-test"
            )
        }
        assertThat(viewModel.state.value.saved).isTrue()
    }
}
