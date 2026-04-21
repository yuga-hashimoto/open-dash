package com.opendash.app.ui.settings.termux

import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.termux.TermuxAvailability
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
class TermuxSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state reflects availability probe on construction`() = runTest {
        val enabledFlow = MutableStateFlow<Boolean?>(null)
        val prefs = prefsReturning(enabledFlow)
        val availability = ToggleableAvailability(installed = true, permitted = false)

        val vm = TermuxSettingsViewModel(prefs, availability)

        vm.state.test {
            // initial synchronous refresh
            val first = awaitItem()
            assertThat(first.termuxInstalled).isTrue()
            assertThat(first.permissionGranted).isFalse()
            assertThat(first.enabled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state mirrors preference changes`() = runTest {
        val enabledFlow = MutableStateFlow<Boolean?>(false)
        val prefs = prefsReturning(enabledFlow)
        val availability = ToggleableAvailability(installed = true, permitted = true)

        val vm = TermuxSettingsViewModel(prefs, availability)

        vm.state.test {
            assertThat(awaitItem().enabled).isFalse()
            // initial collect emits with enabled=false coming from the flow
            advanceUntilIdle()
            enabledFlow.value = true
            assertThat(awaitItem().enabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null preference value is treated as false`() = runTest {
        val enabledFlow = MutableStateFlow<Boolean?>(null)
        val prefs = prefsReturning(enabledFlow)
        val availability = ToggleableAvailability(installed = false, permitted = false)

        val vm = TermuxSettingsViewModel(prefs, availability)

        vm.state.test {
            assertThat(awaitItem().enabled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshAvailability re-probes the availability source`() = runTest {
        val enabledFlow = MutableStateFlow<Boolean?>(true)
        val prefs = prefsReturning(enabledFlow)
        val availability = ToggleableAvailability(installed = false, permitted = false)

        val vm = TermuxSettingsViewModel(prefs, availability)

        vm.state.test {
            // initial probe: both false
            val first = awaitItem()
            assertThat(first.termuxInstalled).isFalse()
            assertThat(first.permissionGranted).isFalse()

            // user installs Termux and grants the permission in system Settings,
            // then comes back — refreshAvailability should pick it up without
            // a full VM rebuild.
            availability.installed = true
            availability.permitted = true
            vm.refreshAvailability()
            advanceUntilIdle()

            val next = awaitItem()
            assertThat(next.termuxInstalled).isTrue()
            assertThat(next.permissionGranted).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setEnabled persists to preferences`() = runTest {
        val enabledFlow = MutableStateFlow<Boolean?>(false)
        val prefs = prefsReturning(enabledFlow)
        val availability = ToggleableAvailability(installed = true, permitted = true)

        val vm = TermuxSettingsViewModel(prefs, availability)

        vm.setEnabled(true)
        advanceUntilIdle()

        coVerify { prefs.set(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED, true) }
    }

    private fun prefsReturning(flow: Flow<Boolean?>): AppPreferences {
        val prefs = mockk<AppPreferences>(relaxed = true)
        every {
            prefs.observe(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED)
        } returns flow
        every { prefs.observe<Any>(any<Preferences.Key<Any>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            flow as Flow<Any?>
        }
        return prefs
    }

    private class ToggleableAvailability(
        var installed: Boolean = false,
        var permitted: Boolean = false
    ) : TermuxAvailability {
        override fun isTermuxInstalled(): Boolean = installed
        override fun hasRunCommandPermission(): Boolean = permitted
    }
}
