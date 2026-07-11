package com.opendash.app.ui.settings.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.spotify.SpotifyAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifySettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val authManager: SpotifyAuthManager
) : ViewModel() {

    data class UiState(
        val clientId: String = "",
        val connected: Boolean = false,
        val redirectUri: String = SpotifyAuthManager.REDIRECT_URI
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.observe(PreferenceKeys.SPOTIFY_CLIENT_ID).collect { value ->
                _state.update { it.copy(clientId = value.orEmpty()) }
            }
        }
        refreshConnectionState()
    }

    /** Call from an onResume-style hook so returning from the browser reflects the new state. */
    fun refreshConnectionState() {
        viewModelScope.launch {
            val connected = authManager.isConnected()
            _state.update { it.copy(connected = connected) }
        }
    }

    fun setClientId(id: String) {
        viewModelScope.launch { preferences.set(PreferenceKeys.SPOTIFY_CLIENT_ID, id) }
    }

    /** Null if no Client ID has been entered yet. */
    suspend fun buildAuthorizationUrl(): String? = authManager.buildAuthorizationUrl()

    fun disconnect() {
        viewModelScope.launch {
            authManager.disconnect()
            refreshConnectionState()
        }
    }
}
