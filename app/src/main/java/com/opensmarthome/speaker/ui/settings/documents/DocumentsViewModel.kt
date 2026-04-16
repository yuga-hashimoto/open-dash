package com.opensmarthome.speaker.ui.settings.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.tool.rag.RagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repository: RagRepository
) : ViewModel() {

    data class UiState(
        val documents: List<RagRepository.DocumentSummary> = emptyList(),
        val loading: Boolean = true,
        val ingesting: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val docs = repository.listDocuments()
            _state.value = _state.value.copy(documents = docs, loading = false)
        }
    }

    fun ingest(title: String, content: String) {
        val trimmedTitle = title.trim()
        val trimmedContent = content.trim()
        if (trimmedTitle.isBlank() || trimmedContent.isBlank()) {
            _state.value = _state.value.copy(message = "Title and content are required")
            return
        }
        _state.value = _state.value.copy(ingesting = true)
        viewModelScope.launch {
            runCatching { repository.ingest(trimmedTitle, trimmedContent) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        ingesting = false,
                        message = "Added '$trimmedTitle'"
                    )
                    refresh()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        ingesting = false,
                        message = "Ingest failed: ${e.message ?: "unknown"}"
                    )
                }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            refresh()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
