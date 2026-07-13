package com.opendash.app.ui.settings.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.assistant.provider.ProviderManager
import com.opendash.app.assistant.provider.embedded.ModelInfo
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P16.6 final-mile: Settings UI to switch the embedded LLM's active model
 * without restarting the app, backed by [ProviderManager.switchEmbeddedModel].
 *
 * User decision (asked directly): [com.opendash.app.assistant.provider.embedded.ModelDownloader]
 * keeps its existing behavior of deleting other models whenever a new one
 * finishes downloading, so the onboarding download flow still only ever
 * produces one model on disk. This picker only has something real to act
 * on when a second model has been added via
 * [com.opendash.app.assistant.provider.embedded.ModelManager.importModel]
 * (manual file import) — with a single model it correctly shows nothing
 * to switch to rather than a pointless one-item picker.
 */
@HiltViewModel
class ModelSwitchViewModel @Inject constructor(
    private val providerManager: ProviderManager,
    private val preferences: AppPreferences
) : ViewModel() {

    data class Row(val model: ModelInfo, val isActive: Boolean)

    enum class SwitchState { IDLE, SWITCHING, FAILED }

    private val _switchState = MutableStateFlow(SwitchState.IDLE)
    val switchState: StateFlow<SwitchState> = _switchState.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())

    val rows: StateFlow<List<Row>> = combine(
        _models,
        preferences.observe(PreferenceKeys.EMBEDDED_LLM_ACTIVE_MODEL_PATH)
    ) { models, activePath ->
        val resolvedActivePath = if (models.any { it.path == activePath }) {
            activePath
        } else {
            models.firstOrNull()?.path
        }
        models.map { Row(it, it.path == resolvedActivePath) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        refresh()
    }

    fun refresh() {
        _models.value = providerManager.getModelManager().listAvailableModels()
    }

    fun switchTo(model: ModelInfo) {
        viewModelScope.launch {
            _switchState.value = SwitchState.SWITCHING
            val ok = providerManager.switchEmbeddedModel(model.path)
            _switchState.value = if (ok) SwitchState.IDLE else SwitchState.FAILED
        }
    }
}
