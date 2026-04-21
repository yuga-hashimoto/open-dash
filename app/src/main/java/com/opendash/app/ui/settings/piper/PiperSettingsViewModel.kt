package com.opendash.app.ui.settings.piper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.tts.TextToSpeech
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
 * installed / downloading / active state derived per-row from the
 * shared [PiperVoiceDownloader] and
 * [PreferenceKeys.PIPER_ACTIVE_VOICE_ID].
 */
@HiltViewModel
class PiperSettingsViewModel @Inject constructor(
    private val downloader: PiperVoiceDownloader,
    private val preferences: AppPreferences,
    /**
     * Injected singleton [TextToSpeech] (typically [com.opendash.app.voice.tts.TtsManager])
     * used by [preview] to speak a sample sentence through whichever
     * voice the user tapped. Preview first persists the voice as active
     * then calls `speak`, so the sample is synthesised through the very
     * voice the user is about to commit to.
     */
    private val tts: TextToSpeech
) : ViewModel() {

    data class Row(
        val voice: PiperVoice,
        val installed: Boolean,
        val isActive: Boolean,
        val isDownloading: Boolean,
        val progress: Float,
        val downloadedMb: Int,
        val totalMb: Int,
        val activeFile: String?
    )

    private val _activeDownloadId = MutableStateFlow<String?>(null)

    val rows: StateFlow<List<Row>> = combine(
        downloader.state,
        _activeDownloadId,
        preferences.observe(PreferenceKeys.PIPER_ACTIVE_VOICE_ID)
    ) { state, activeDl, activePref ->
        val activeId = activePref ?: PiperVoiceCatalog.default.id
        PiperVoiceCatalog.all.map { voice ->
            val isActiveDl = activeDl == voice.id &&
                state is PiperVoiceDownloader.State.Downloading
            val dl = state as? PiperVoiceDownloader.State.Downloading
            Row(
                voice = voice,
                installed = downloader.isDownloaded(voice),
                isActive = voice.id == activeId,
                isDownloading = isActiveDl,
                progress = if (isActiveDl && dl != null) dl.progress else 0f,
                downloadedMb = if (isActiveDl && dl != null) dl.downloadedMb else 0,
                totalMb = if (isActiveDl && dl != null) dl.totalMb else voice.modelSizeMb,
                activeFile = if (isActiveDl && dl != null) dl.file else null
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PiperVoiceCatalog.all.map { voice ->
            Row(
                voice = voice,
                installed = false,
                isActive = voice.id == PiperVoiceCatalog.default.id,
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

    fun setActive(voice: PiperVoice) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.PIPER_ACTIVE_VOICE_ID, voice.id)
        }
    }

    /**
     * Preview [voice] by persisting it as active and then speaking a
     * localized sample through the [TtsManager]-backed [tts] singleton.
     * No-op when the voice isn't downloaded — the Compose card disables
     * the button in that state.
     *
     * Sample text is intentionally short (one sentence) so the user
     * gets a quick taste without a 5-second synthesis wait on
     * tiny-class voices.
     */
    fun preview(voice: PiperVoice) {
        if (!downloader.isDownloaded(voice)) return
        viewModelScope.launch {
            preferences.set(PreferenceKeys.PIPER_ACTIVE_VOICE_ID, voice.id)
            runCatching { tts.speak(sampleTextFor(voice)) }
                .onFailure { Timber.w(it, "Piper preview failed for ${voice.id}") }
        }
    }

    private fun sampleTextFor(voice: PiperVoice): String = when {
        voice.languageTag.startsWith("ja") -> "こんにちは、これは ${voice.displayName} のサンプルです。"
        else -> "Hello, this is a sample of ${voice.displayName}."
    }
}
