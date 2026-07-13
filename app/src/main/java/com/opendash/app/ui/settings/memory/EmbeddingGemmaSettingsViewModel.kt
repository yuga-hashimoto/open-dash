package com.opendash.app.ui.settings.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.tool.memory.embedding.EmbeddingGemmaModelCatalog
import com.opendash.app.tool.memory.embedding.EmbeddingGemmaModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P16.4 final-mile: Settings UI for the EmbeddingGemma model download.
 *
 * Same shape as [com.opendash.app.ui.settings.vad.SileroVadSettingsViewModel] —
 * single model, no catalogue, auto-upgrades `SemanticMemorySearch`'s
 * embedder the moment it's on disk (see `DeviceModule.buildSemanticEmbedderIfDownloaded`).
 * The 183MB size is the largest optional download in this app, which is
 * exactly why leaving it undiscoverable (no UI to trigger it) mattered
 * enough to fix — a user would otherwise never find out semantic memory
 * search could be upgraded past TF-IDF at all.
 */
@HiltViewModel
class EmbeddingGemmaSettingsViewModel @Inject constructor(
    private val downloader: EmbeddingGemmaModelDownloader
) : ViewModel() {

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    data class UiState(
        val status: Status,
        val progress: Float = 0f,
        val downloadedMb: Int = 0,
        val totalMb: Int = 0,
        val errorMessage: String? = null
    )

    val sizeMb: Int = (EmbeddingGemmaModelCatalog.SIZE_BYTES / 1_048_576L).toInt()

    val uiState: StateFlow<UiState> = downloader.state.map { s ->
        when (s) {
            is EmbeddingGemmaModelDownloader.State.Downloading ->
                UiState(Status.DOWNLOADING, s.progress, s.downloadedMb, s.totalMb)
            is EmbeddingGemmaModelDownloader.State.Error ->
                UiState(Status.ERROR, totalMb = sizeMb, errorMessage = s.message)
            EmbeddingGemmaModelDownloader.State.Ready ->
                UiState(Status.READY, totalMb = sizeMb)
            EmbeddingGemmaModelDownloader.State.NotStarted ->
                if (downloader.isDownloaded()) UiState(Status.READY, totalMb = sizeMb)
                else UiState(Status.NOT_DOWNLOADED, totalMb = sizeMb)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = if (downloader.isDownloaded()) {
            UiState(Status.READY, totalMb = sizeMb)
        } else {
            UiState(Status.NOT_DOWNLOADED, totalMb = sizeMb)
        }
    )

    private var downloadJob: Job? = null

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            downloader.download()
        }
    }

    fun delete() {
        downloader.deleteModel()
    }
}
