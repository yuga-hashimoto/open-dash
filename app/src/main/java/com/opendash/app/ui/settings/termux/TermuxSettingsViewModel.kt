package com.opendash.app.ui.settings.termux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.termux.TermuxAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P19.2 Settings UI surface for the Termux Bridge opt-in.
 *
 * Unifies the three gates so the card can render a single honest status
 * line instead of the user flipping the switch and wondering why nothing
 * happened:
 *
 *  - [UiState.enabled] — the [PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED]
 *    opt-in flag (observed, so programmatic writes stay in sync).
 *  - [UiState.termuxInstalled] — whether `com.termux` is visible to
 *    PackageManager (subject to the manifest `<queries>` entry).
 *  - [UiState.permissionGranted] — whether the app currently holds
 *    `com.termux.permission.RUN_COMMAND`. Re-queried on
 *    [refreshAvailability] so coming back from a Settings grant updates
 *    the card without an app restart.
 *
 * The card derives `gatesSatisfied = enabled && installed && permission`
 * in Compose rather than here so mid-toggle intermediate states can still
 * show a useful hint about what's still missing.
 */
@HiltViewModel
class TermuxSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val availability: TermuxAvailability
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val termuxInstalled: Boolean = false,
        val permissionGranted: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshAvailability()
        viewModelScope.launch {
            preferences.observe(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED).collect { value ->
                _state.update { it.copy(enabled = value ?: false) }
            }
        }
    }

    /**
     * Re-probe PackageManager and permission state. Call from an
     * `onResume`-style hook so toggling Termux install / permission in
     * system Settings is reflected without relaunching the app.
     */
    fun refreshAvailability() {
        _state.update {
            it.copy(
                termuxInstalled = availability.isTermuxInstalled(),
                permissionGranted = availability.hasRunCommandPermission()
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED, enabled)
        }
    }
}
