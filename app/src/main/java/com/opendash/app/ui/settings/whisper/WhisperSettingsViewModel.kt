package com.opendash.app.ui.settings.whisper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.voice.stt.whisper.WhisperModel
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * P14.1 final-mile: Settings UI for the Whisper STT model catalogue.
 *
 * Backs the rows in `WhisperModelRows`:
 *
 *  - Each catalogue entry renders with display name + size + an action
 *    (Download / Cancel / Delete / Installed check)
 *  - The single active download (at most one) is surfaced as a progress
 *    value via [WhisperDownloadRow] so the user can see what's in
 *    flight without refreshing
 *  - Installed models and in-progress downloads are derived per-row in
 *    [rows] so the UI can stay stateless over collectAsState
 */
@HiltViewModel
class WhisperSettingsViewModel @Inject constructor(
    private val downloader: WhisperModelDownloader
) : ViewModel() {

    data class Row(
        val model: WhisperModel,
        val installed: Boolean,
        val isDownloading: Boolean,
        val progress: Float,
        val downloadedMb: Int,
        val totalMb: Int
    )

    private val _activeDownloadId = MutableStateFlow<String?>(null)

    /**
     * Rows for every catalogue entry. Combines the download-state Flow
     * with the single-slot "which model is downloading right now" flag
     * so a row only reports `isDownloading=true` when THIS model is the
     * one the user just tapped (not any other row that finished last).
     */
    val rows: StateFlow<List<Row>> = combine(
        downloader.state,
        _activeDownloadId
    ) { state, activeId ->
        WhisperModelCatalog.all.map { model ->
            val isActive = activeId == model.id &&
                state is WhisperModelDownloader.State.Downloading
            val downloading = state as? WhisperModelDownloader.State.Downloading
            Row(
                model = model,
                installed = downloader.isDownloaded(model),
                isDownloading = isActive,
                progress = if (isActive && downloading != null) downloading.progress else 0f,
                downloadedMb = if (isActive && downloading != null) downloading.downloadedMb else 0,
                totalMb = if (isActive && downloading != null) downloading.totalMb else model.sizeMb
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WhisperModelCatalog.all.map { model ->
            Row(
                model = model,
                installed = false,
                isDownloading = false,
                progress = 0f,
                downloadedMb = 0,
                totalMb = model.sizeMb
            )
        }
    )

    private var downloadJob: Job? = null

    fun startDownload(model: WhisperModel) {
        if (downloadJob?.isActive == true) {
            Timber.d("Whisper download already in flight, ignoring second request")
            return
        }
        _activeDownloadId.value = model.id
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(model)
            } finally {
                _activeDownloadId.value = null
            }
        }
    }

    fun delete(model: WhisperModel) {
        downloader.deleteModel(model)
    }
}
