package com.opensmarthome.speaker.ui.settings.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.data.db.ToolUsageEntity
import com.opensmarthome.speaker.tool.analytics.AnalyticsRepository
import com.opensmarthome.speaker.voice.metrics.LatencyRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: AnalyticsRepository,
    private val latencyRecorder: LatencyRecorder
) : ViewModel() {

    data class LatencyRow(
        val event: String,
        val count: Int,
        val averageMs: Long,
        val p95Ms: Long,
        val budgetMs: Long,
        val violations: Int
    )

    data class UiState(
        val summary: AnalyticsRepository.Summary? = null,
        val allTime: List<ToolUsageEntity> = emptyList(),
        val latency: List<LatencyRow> = emptyList(),
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
            val violations = latencyRecorder.budgetViolations()
            val latency = latencyRecorder.summarize().map { s ->
                val span = runCatching { LatencyRecorder.Span.valueOf(s.event) }.getOrNull()
                LatencyRow(
                    event = s.event,
                    count = s.count,
                    averageMs = s.averageMs,
                    p95Ms = s.p95Ms,
                    budgetMs = span?.budgetMs ?: 0,
                    violations = span?.let { violations[it] } ?: 0
                )
            }
            _state.value = UiState(
                summary = summary,
                allTime = allTime,
                latency = latency,
                loading = false
            )
        }
    }

    fun reset() {
        viewModelScope.launch {
            repository.reset()
            latencyRecorder.reset()
            refresh()
        }
    }
}
