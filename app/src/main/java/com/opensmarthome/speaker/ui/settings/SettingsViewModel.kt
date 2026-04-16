package com.opensmarthome.speaker.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.data.preferences.AppPreferences
import com.opensmarthome.speaker.data.preferences.PreferenceKeys
import com.opensmarthome.speaker.data.preferences.SecurePreferences
import com.opensmarthome.speaker.voice.tts.AndroidTtsProvider
import com.opensmarthome.speaker.voice.tts.TextToSpeech
import com.opensmarthome.speaker.voice.tts.TtsUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    private val tts: TextToSpeech,
    private val application: Application
) : ViewModel() {

    // Connection settings
    private val _haBaseUrl = MutableStateFlow("")
    val haBaseUrl: StateFlow<String> = _haBaseUrl.asStateFlow()

    private val _haToken = MutableStateFlow("")
    val haToken: StateFlow<String> = _haToken.asStateFlow()

    private val _openClawUrl = MutableStateFlow("")
    val openClawUrl: StateFlow<String> = _openClawUrl.asStateFlow()

    private val _localLlmUrl = MutableStateFlow("http://localhost:8080")
    val localLlmUrl: StateFlow<String> = _localLlmUrl.asStateFlow()

    private val _localLlmModel = MutableStateFlow("gemma-4-e2b")
    val localLlmModel: StateFlow<String> = _localLlmModel.asStateFlow()

    private val _switchBotToken = MutableStateFlow("")
    val switchBotToken: StateFlow<String> = _switchBotToken.asStateFlow()

    private val _switchBotSecret = MutableStateFlow("")
    val switchBotSecret: StateFlow<String> = _switchBotSecret.asStateFlow()

    private val _mqttBrokerUrl = MutableStateFlow("")
    val mqttBrokerUrl: StateFlow<String> = _mqttBrokerUrl.asStateFlow()

    // Wake word
    private val _wakeWord = MutableStateFlow("hey speaker")
    val wakeWord: StateFlow<String> = _wakeWord.asStateFlow()

    // TTS settings
    private val _ttsSpeechRate = MutableStateFlow(1.0f)
    val ttsSpeechRate: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(1.0f)
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    private val _ttsEngine = MutableStateFlow("")
    val ttsEngine: StateFlow<String> = _ttsEngine.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _availableEngines = MutableStateFlow<List<TtsUtils.EngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<TtsUtils.EngineInfo>> = _availableEngines.asStateFlow()

    // Voice interaction settings
    private val _continuousMode = MutableStateFlow(false)
    val continuousMode: StateFlow<Boolean> = _continuousMode.asStateFlow()

    private val _thinkingSound = MutableStateFlow(true)
    val thinkingSound: StateFlow<Boolean> = _thinkingSound.asStateFlow()

    private val _bargeInEnabled = MutableStateFlow(true)
    val bargeInEnabled: StateFlow<Boolean> = _bargeInEnabled.asStateFlow()

    private val _silenceTimeoutMs = MutableStateFlow(1500L)
    val silenceTimeoutMs: StateFlow<Long> = _silenceTimeoutMs.asStateFlow()

    private val _mediaButtonEnabled = MutableStateFlow(false)
    val mediaButtonEnabled: StateFlow<Boolean> = _mediaButtonEnabled.asStateFlow()

    // STT language
    private val _sttLanguage = MutableStateFlow("")
    val sttLanguage: StateFlow<String> = _sttLanguage.asStateFlow()

    init {
        viewModelScope.launch { preferences.observe(PreferenceKeys.HA_BASE_URL).collect { _haBaseUrl.value = it ?: "" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.OPENCLAW_GATEWAY_URL).collect { _openClawUrl.value = it ?: "" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL).collect { _localLlmUrl.value = it ?: "http://localhost:8080" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL).collect { _localLlmModel.value = it ?: "gemma-4-e2b" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.SWITCHBOT_TOKEN).collect { _switchBotToken.value = it ?: "" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.MQTT_BROKER_URL).collect { _mqttBrokerUrl.value = it ?: "" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.WAKE_WORD).collect { _wakeWord.value = it ?: "hey speaker" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.TTS_SPEECH_RATE).collect { _ttsSpeechRate.value = it ?: 1.0f } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.TTS_PITCH).collect { _ttsPitch.value = it ?: 1.0f } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.TTS_ENGINE).collect { _ttsEngine.value = it ?: "" } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.TTS_ENABLED).collect { _ttsEnabled.value = it ?: true } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.CONTINUOUS_MODE).collect { _continuousMode.value = it ?: false } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.THINKING_SOUND).collect { _thinkingSound.value = it ?: true } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.BARGE_IN_ENABLED).collect { _bargeInEnabled.value = it ?: true } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.SILENCE_TIMEOUT_MS).collect { _silenceTimeoutMs.value = it ?: 1500L } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.MEDIA_BUTTON_ENABLED).collect { _mediaButtonEnabled.value = it ?: false } }
        viewModelScope.launch { preferences.observe(PreferenceKeys.STT_LANGUAGE).collect { _sttLanguage.value = it ?: "" } }

        _haToken.value = securePreferences.getString(SecurePreferences.KEY_HA_TOKEN)
        _switchBotSecret.value = securePreferences.getString("switchbot_secret")
        _availableEngines.value = TtsUtils.getAvailableEngines(application)
    }

    // Connection settings
    fun saveHaSettings(baseUrl: String, token: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.HA_BASE_URL, baseUrl)
            securePreferences.putString(SecurePreferences.KEY_HA_TOKEN, token)
            _haToken.value = token
        }
    }

    fun saveOpenClawSettings(url: String) {
        viewModelScope.launch { preferences.set(PreferenceKeys.OPENCLAW_GATEWAY_URL, url) }
    }

    fun saveLocalLlmSettings(url: String, model: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.LOCAL_LLM_BASE_URL, url)
            preferences.set(PreferenceKeys.LOCAL_LLM_MODEL, model)
        }
    }

    fun saveSwitchBotSettings(token: String, secret: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.SWITCHBOT_TOKEN, token)
            securePreferences.putString("switchbot_secret", secret)
            _switchBotToken.value = token
            _switchBotSecret.value = secret
        }
    }

    fun saveMqttSettings(brokerUrl: String) {
        viewModelScope.launch { preferences.set(PreferenceKeys.MQTT_BROKER_URL, brokerUrl) }
    }

    fun saveWakeWord(word: String) {
        viewModelScope.launch { preferences.set(PreferenceKeys.WAKE_WORD, word.lowercase().trim()) }
    }

    // TTS settings
    fun saveTtsSpeechRate(rate: Float) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.TTS_SPEECH_RATE, rate)
            (tts as? AndroidTtsProvider)?.setSpeechRate(rate)
        }
    }

    fun saveTtsPitch(pitch: Float) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.TTS_PITCH, pitch)
            (tts as? AndroidTtsProvider)?.setPitch(pitch)
        }
    }

    fun saveTtsEngine(engine: String) {
        viewModelScope.launch {
            preferences.set(PreferenceKeys.TTS_ENGINE, engine)
            (tts as? AndroidTtsProvider)?.reinitialize(engine)
        }
    }

    fun saveTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.set(PreferenceKeys.TTS_ENABLED, enabled) }
    }

    // Voice interaction settings
    fun saveContinuousMode(enabled: Boolean) {
        viewModelScope.launch { preferences.set(PreferenceKeys.CONTINUOUS_MODE, enabled) }
    }

    fun saveThinkingSound(enabled: Boolean) {
        viewModelScope.launch { preferences.set(PreferenceKeys.THINKING_SOUND, enabled) }
    }

    fun saveBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.set(PreferenceKeys.BARGE_IN_ENABLED, enabled) }
    }

    fun saveSilenceTimeout(ms: Long) {
        viewModelScope.launch { preferences.set(PreferenceKeys.SILENCE_TIMEOUT_MS, ms) }
    }

    fun saveMediaButtonEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.set(PreferenceKeys.MEDIA_BUTTON_ENABLED, enabled) }
    }

    // STT language
    fun saveSttLanguage(lang: String) {
        viewModelScope.launch { preferences.set(PreferenceKeys.STT_LANGUAGE, lang) }
    }
}
