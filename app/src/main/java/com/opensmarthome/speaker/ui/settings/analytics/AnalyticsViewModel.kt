package com.opensmarthome.speaker.ui.settings.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.data.db.ToolUsageEntity
import com.opensmarthome.speaker.tool.analytics.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: AnalyticsRepository
) : ViewModel() {

    data class UiState(
        val summary: AnalyticsRepository.Summary? = null,
        val allTime: List<ToolUsageEntity> = emptyList(),
        val loading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val summary = repository.summary()
            val allTime = repository.allTime()
            _state.value = UiState(summary = summary, allTime = allTime, loading = false)
        }
    }

    fun reset() {
        viewModelScope.launch {
            repository.reset()
            refresh()
        }
    }
}
