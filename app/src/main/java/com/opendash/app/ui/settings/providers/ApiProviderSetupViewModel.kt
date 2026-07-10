package com.opendash.app.ui.settings.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.assistant.provider.api.ApiProviderCatalog
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.api.ModelListFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ApiProviderSetupState(
    val selectedPreset: ApiProviderCatalog.Preset? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val isFetchingModels: Boolean = false,
    val modelFetchFailed: Boolean = false,
    val saved: Boolean = false
)

@HiltViewModel
class ApiProviderSetupViewModel @Inject constructor(
    private val store: ApiProviderConfigStore,
    private val fetcher: ModelListFetcher
) : ViewModel() {

    private val _state = MutableStateFlow(ApiProviderSetupState())
    val state: StateFlow<ApiProviderSetupState> = _state.asStateFlow()

    fun selectPreset(preset: ApiProviderCatalog.Preset) {
        _state.update {
            it.copy(
                selectedPreset = preset,
                displayName = preset.displayName,
                baseUrl = preset.defaultBaseUrl,
                availableModels = emptyList(),
                selectedModel = "",
                modelFetchFailed = false
            )
        }
    }

    fun updateDisplayName(name: String) {
        _state.update { it.copy(displayName = name) }
    }

    fun updateBaseUrl(url: String) {
        _state.update { it.copy(baseUrl = url) }
    }

    fun updateApiKey(key: String) {
        _state.update { it.copy(apiKey = key) }
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(selectedModel = modelId) }
    }

    fun fetchModels() {
        val current = _state.value
        val authStyle = current.selectedPreset?.authStyle ?: "bearer"
        viewModelScope.launch {
            _state.update { it.copy(isFetchingModels = true, modelFetchFailed = false) }
            val result = fetcher.fetch(current.baseUrl, current.apiKey, authStyle)
            result.fold(
                onSuccess = { models ->
                    _state.update {
                        it.copy(isFetchingModels = false, availableModels = models, modelFetchFailed = false)
                    }
                },
                onFailure = {
                    _state.update { it.copy(isFetchingModels = false, modelFetchFailed = true) }
                }
            )
        }
    }

    fun save() {
        val current = _state.value
        val preset = current.selectedPreset ?: return
        viewModelScope.launch {
            val config = ApiProviderConfig(
                id = UUID.randomUUID().toString(),
                presetId = preset.id,
                displayName = current.displayName,
                baseUrl = current.baseUrl,
                modelId = current.selectedModel,
                authStyle = preset.authStyle,
                createdAt = System.currentTimeMillis()
            )
            store.add(config, current.apiKey)
            _state.update { it.copy(saved = true) }
        }
    }
}
