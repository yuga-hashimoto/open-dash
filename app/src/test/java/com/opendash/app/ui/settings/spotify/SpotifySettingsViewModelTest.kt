package com.opendash.app.ui.settings.spotify

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifySettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferences: AppPreferences
    private lateinit var authManager: SpotifyAuthManager
    private val clientIdFlow = MutableStateFlow<String?>(null)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        preferences = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        every { preferences.observe(PreferenceKeys.SPOTIFY_CLIENT_ID) } returns clientIdFlow
        coEvery { authManager.isConnected() } returns false
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state mirrors client id preference`() = runTest {
        val vm = SpotifySettingsViewModel(preferences, authManager)

        vm.state.test {
            assertThat(awaitItem().clientId).isEmpty()
            clientIdFlow.value = "abc123"
            assertThat(awaitItem().clientId).isEqualTo("abc123")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects connection status on construction`() = runTest {
        coEvery { authManager.isConnected() } returns true

        val vm = SpotifySettingsViewModel(preferences, authManager)
        advanceUntilIdle()

        assertThat(vm.state.value.connected).isTrue()
    }

    @Test
    fun `setClientId persists to preferences`() = runTest {
        val vm = SpotifySettingsViewModel(preferences, authManager)

        vm.setClientId("new-client-id")
        advanceUntilIdle()

        coVerify { preferences.set(PreferenceKeys.SPOTIFY_CLIENT_ID, "new-client-id") }
    }

    @Test
    fun `refreshConnectionState re-queries the auth manager`() = runTest {
        val vm = SpotifySettingsViewModel(preferences, authManager)
        advanceUntilIdle()
        assertThat(vm.state.value.connected).isFalse()

        coEvery { authManager.isConnected() } returns true
        vm.refreshConnectionState()
        advanceUntilIdle()

        assertThat(vm.state.value.connected).isTrue()
    }

    @Test
    fun `disconnect calls authManager and refreshes state`() = runTest {
        val vm = SpotifySettingsViewModel(preferences, authManager)

        vm.disconnect()
        advanceUntilIdle()

        coVerify { authManager.disconnect() }
    }

    @Test
    fun `buildAuthorizationUrl delegates to authManager`() = runTest {
        coEvery { authManager.buildAuthorizationUrl() } returns "https://accounts.spotify.com/authorize?..."

        val vm = SpotifySettingsViewModel(preferences, authManager)
        val url = vm.buildAuthorizationUrl()

        assertThat(url).isEqualTo("https://accounts.spotify.com/authorize?...")
    }
}
