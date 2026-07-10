package com.opendash.app.ui.onboarding

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appPreferences: AppPreferences
    private lateinit var viewModel: ProviderModeViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        appPreferences = mockk(relaxed = true)
        every { appPreferences.observe(PreferenceKeys.ASSISTANT_MODE) } returns flowOf(null)
        viewModel = ProviderModeViewModel(appPreferences)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectMode local persists MODE_LOCAL`() = runTest {
        viewModel.selectMode(PreferenceKeys.MODE_LOCAL)
        advanceUntilIdle()

        coVerify { appPreferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_LOCAL) }
    }

    @Test
    fun `selectMode api persists MODE_API`() = runTest {
        viewModel.selectMode(PreferenceKeys.MODE_API)
        advanceUntilIdle()

        coVerify { appPreferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API) }
    }
}
