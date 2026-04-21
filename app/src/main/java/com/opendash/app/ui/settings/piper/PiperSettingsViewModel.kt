package com.opendash.app.ui.settings.piper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.voice.tts.piper.PiperVoice
import com.opendash.app.voice.tts.piper.PiperVoiceCatalog
import com.opendash.app.voice.tts.piper.PiperVoiceDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * P14.9 Settings-side VM for the Piper voice catalogue. Parallels
 * `WhisperSettingsViewModel` — one row per catalogue entry, with
 * installed / downloading state derived per-row from the shared
 * [PiperVoiceDownloader] state.
 */
@HiltViewModel
class PiperSettingsViewModel @Inject constructor(
    private val downloader: PiperVoiceDownloader
) : ViewModel() {

    data class Row(
        val voice: PiperVoice,
        val installed: Boolean,
        val isDownloading: Boolean,
        val progress: Float,
        val downloadedMb: Int,
        val totalMb: Int,
        val activeFile: String?
    )

    private val _activeDownloadId = MutableStateFlow<String?>(null)

    val rows: StateFlow<List<Row>> = combine(
        downloader.state,
        _activeDownloadId
    ) { state, activeId ->
        PiperVoiceCatalog.all.map { voice ->
            val isActive = activeId == voice.id &&
                state is PiperVoiceDownloader.State.Downloading
            val dl = state as? PiperVoiceDownloader.State.Downloading
            Row(
                voice = voice,
                installed = downloader.isDownloaded(voice),
                isDownloading = isActive,
                progress = if (isActive && dl != null) dl.progress else 0f,
                downloadedMb = if (isActive && dl != null) dl.downloadedMb else 0,
                totalMb = if (isActive && dl != null) dl.totalMb else voice.modelSizeMb,
                activeFile = if (isActive && dl != null) dl.file else null
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PiperVoiceCatalog.all.map { voice ->
            Row(
                voice = voice,
                installed = false,
                isDownloading = false,
                progress = 0f,
                downloadedMb = 0,
                totalMb = voice.modelSizeMb,
                activeFile = null
            )
        }
    )

    private var downloadJob: Job? = null

    fun startDownload(voice: PiperVoice) {
        if (downloadJob?.isActive == true) {
            Timber.d("Piper voice download already in flight, ignoring second request")
            return
        }
        _activeDownloadId.value = voice.id
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(voice)
            } finally {
                _activeDownloadId.value = null
            }
        }
    }

    fun delete(voice: PiperVoice) {
        downloader.deleteVoice(voice)
    }
}
