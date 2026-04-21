package com.opendash.app.ui.settings.whisper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.stt.whisper.WhisperModel
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
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
 * P14.1 final-mile: Settings UI for the Whisper STT model catalogue.
 *
 * Backs the rows in [WhisperModelsCard]:
 *
 *  - Each catalogue entry renders with display name + size + an action
 *    (Download / Delete) and an "active" radio marker
 *  - The single active download (at most one) is surfaced as a progress
 *    value so the user can see what's in flight without refreshing
 *  - Only one model can be "active" at a time; `WHISPER_ACTIVE_MODEL_ID`
 *    drives [com.opendash.app.di.SttModule.resolveWhisperModelFile]
 */
@HiltViewModel
class WhisperSettingsViewModel @Inject constructor(
    private val downloader: WhisperModelDownloader,
    private val preferences: AppPreferences
) : ViewModel() {

    data class Row(
        val model: WhisperModel,
        val installed: Boolean,
        val isActive: Boolean,
        val isDownloading: Boolean,
        val progress: Float,
        val downloadedMb: Int,
        val totalMb: Int
    )

    private val _activeDownloadId = MutableStateFlow<String?>(null)

    /**
     * Rows for every catalogue entry. 3-way combine across downloader
     * state, "what's downloading right now", and the persisted active
     * model id — a row reports `isDownloading=true` only when THIS
     * model is the one the user just tapped, and `isActive=true` only
     * when it matches the persisted preference.
     */
    val rows: StateFlow<List<Row>> = combine(
        downloader.state,
        _activeDownloadId,
        preferences.observe(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID)
    ) { state, activeDl, activePref ->
        val activeId = activePref ?: WhisperModelCatalog.default.id
        WhisperModelCatalog.all.map { model ->
            val isActiveDl = activeDl == model.id &&
                state is WhisperModelDownloader.State.Downloading
            val downloading = state as? WhisperModelDownloader.State.Downloading
            Row(
                model = model,
                installed = downloader.isDownloaded(model),
                isActive = model.id == activeId,
                isDownloading = isActiveDl,
                progress = if (isActiveDl && downloading != null) downloading.progress else 0f,
                downloadedMb = if (isActiveDl && downloading != null) downloading.downloadedMb else 0,
                totalMb = if (isActiveDl && downloading != null) downloading.totalMb else model.sizeMb
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WhisperModelCatalog.all.map { model ->
            Row(
                model = model,
                installed = false,
                isActive = model.id == WhisperModelCatalog.default.id,
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

    /**
     * Persist [model] as the active Whisper model. `WhisperSttProvider`
     * picks this up at next `startListening()` call — no pipeline reset
     * needed because `modelPathProvider` reads the preference each time.
     */
    fun setActive(model: WhisperModel) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID, model.id)
        }
    }

    /**
     * Current WHISPER_LANGUAGE preference mapped to one of
     * [SUPPORTED_LANGUAGES]. Empty / unset renders as "auto".
     * WhisperSttProvider reads the preference each `startListening()`
     * so flipping this picker takes effect on the next utterance.
     */
    val language: StateFlow<String> = preferences
        .observe(PreferenceKeys.WHISPER_LANGUAGE)
        .let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow("auto").also { backing ->
                viewModelScope.launch {
                    flow.collect { v ->
                        backing.value = v?.trim()?.ifBlank { null } ?: "auto"
                    }
                }
            }
        }

    fun setLanguage(code: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.WHISPER_LANGUAGE, code)
        }
    }

    companion object {
        /**
         * Whitelist for the picker. whisper.cpp speaks 99 languages; we
         * only expose the ones the OpenDash household is likely to use
         * + "auto" so the picker stays a 3-row radio instead of a
         * dropdown. More can land as follow-ups.
         */
        val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
            "auto" to "Auto-detect",
            "en" to "English",
            "ja" to "日本語"
        )
    }
}
