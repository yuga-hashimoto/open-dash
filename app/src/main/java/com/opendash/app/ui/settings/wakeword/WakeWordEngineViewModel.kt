package com.opendash.app.ui.settings.wakeword

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.service.VoiceService
import com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * P21.7 final-mile: lets the user opt into openWakeWord as an alternative
 * wake-word engine. Default stays [VoiceService.ENGINE_VOSK] for everyone —
 * this is a Settings-gated opt-in, never an auto-upgrade, since openWakeWord
 * only recognizes the fixed English "hey jarvis" phrase rather than the
 * user's configured [PreferenceKeys.WAKE_WORD].
 */
@HiltViewModel
class WakeWordEngineViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences
) : ViewModel() {

    enum class DownloadStatus { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    data class UiState(
        val selectedEngine: String,
        val downloadStatus: DownloadStatus,
        val errorMessage: String? = null
    )

    private val downloader = OpenWakeWordModelDownloader(context)

    val uiState: StateFlow<UiState> = combine(
        preferences.observe(PreferenceKeys.WAKE_WORD_ENGINE),
        downloader.state
    ) { savedEngine, downloadState ->
        val engine = savedEngine ?: VoiceService.ENGINE_VOSK
        val status = when (downloadState) {
            is OpenWakeWordModelDownloader.State.Downloading -> DownloadStatus.DOWNLOADING
            is OpenWakeWordModelDownloader.State.Error -> DownloadStatus.ERROR
            OpenWakeWordModelDownloader.State.Ready -> DownloadStatus.READY
            OpenWakeWordModelDownloader.State.NotStarted ->
                if (downloader.allDownloaded()) DownloadStatus.READY else DownloadStatus.NOT_DOWNLOADED
        }
        UiState(
            selectedEngine = engine,
            downloadStatus = status,
            errorMessage = (downloadState as? OpenWakeWordModelDownloader.State.Error)?.message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(
            selectedEngine = VoiceService.ENGINE_VOSK,
            downloadStatus = if (downloader.allDownloaded()) DownloadStatus.READY else DownloadStatus.NOT_DOWNLOADED
        )
    )

    private var downloadJob: Job? = null

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            downloader.downloadAll()
        }
    }

    fun selectEngine(engine: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.WAKE_WORD_ENGINE, engine)
        }
    }
}
