package com.opendash.app.ui.settings.vad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.voice.vad.silero.SileroVadModelCatalog
import com.opendash.app.voice.vad.silero.SileroVadModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P16.2 final-mile: Settings UI for the Silero VAD model download.
 *
 * Unlike [com.opendash.app.ui.settings.whisper.WhisperSettingsViewModel]
 * there's no catalogue of choices and no "active" selection — it's a
 * single model that auto-upgrades [com.opendash.app.voice.stt.whisper.AudioRecordPcmSource]'s
 * VAD the moment it's on disk (see `SttModule.buildVadEngine`), so this
 * ViewModel only needs to expose "not downloaded / downloading / ready /
 * error" plus a download/delete action.
 */
@HiltViewModel
class SileroVadSettingsViewModel @Inject constructor(
    private val downloader: SileroVadModelDownloader
) : ViewModel() {

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    data class UiState(
        val status: Status,
        val progress: Float = 0f,
        val downloadedMb: Int = 0,
        val totalMb: Int = 0,
        val errorMessage: String? = null
    )

    val sizeMb: Int = (SileroVadModelCatalog.SIZE_BYTES / 1_048_576L).toInt()

    /**
     * [SileroVadModelDownloader.state] always starts at `NotStarted` on a
     * fresh process, even if a previous session already finished the
     * download — it's in-memory transient state, not derived from disk.
     * So a bare model file check (like [WhisperSettingsViewModel.rows]'s
     * `installed` flag) is needed alongside it to correctly show "ready"
     * after an app restart, not just mid-download.
     */
    val uiState: StateFlow<UiState> = downloader.state.map { s ->
        when (s) {
            is SileroVadModelDownloader.State.Downloading ->
                UiState(Status.DOWNLOADING, s.progress, s.downloadedMb, s.totalMb)
            is SileroVadModelDownloader.State.Error ->
                UiState(Status.ERROR, totalMb = sizeMb, errorMessage = s.message)
            SileroVadModelDownloader.State.Ready ->
                UiState(Status.READY, totalMb = sizeMb)
            SileroVadModelDownloader.State.NotStarted ->
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
