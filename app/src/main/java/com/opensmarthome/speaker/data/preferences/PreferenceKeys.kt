package com.opensmarthome.speaker.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferenceKeys {
    // TTS
    val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
    val TTS_PITCH = floatPreferencesKey("tts_pitch")
    val TTS_ENGINE = stringPreferencesKey("tts_engine")
    val TTS_LANGUAGE = stringPreferencesKey("tts_language")
    val TTS_ENABLED = booleanPreferencesKey("tts_enabled")

    // Voice interaction
    val CONTINUOUS_MODE = booleanPreferencesKey("continuous_mode")
    val THINKING_SOUND = booleanPreferencesKey("thinking_sound")
    val BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
    val SILENCE_TIMEOUT_MS = longPreferencesKey("silence_timeout_ms")
    val MEDIA_BUTTON_ENABLED = booleanPreferencesKey("media_button_enabled")

    // STT
    val STT_LANGUAGE = stringPreferencesKey("stt_language")

    // Home Assistant
    val HA_BASE_URL = stringPreferencesKey("ha_base_url")
    val HA_TOKEN = stringPreferencesKey("ha_token")

    // OpenClaw
    val OPENCLAW_GATEWAY_URL = stringPreferencesKey("openclaw_gateway_url")
    val OPENCLAW_API_KEY = stringPreferencesKey("openclaw_api_key")

    // Local LLM
    val LOCAL_LLM_BASE_URL = stringPreferencesKey("local_llm_base_url")
    val LOCAL_LLM_MODEL = stringPreferencesKey("local_llm_model")

    // Routing
    val ROUTING_POLICY = stringPreferencesKey("routing_policy")
    val ACTIVE_PROVIDER_ID = stringPreferencesKey("active_provider_id")

    // SwitchBot
    val SWITCHBOT_TOKEN = stringPreferencesKey("switchbot_token")
    val SWITCHBOT_SECRET = stringPreferencesKey("switchbot_secret")

    // MQTT
    val MQTT_BROKER_URL = stringPreferencesKey("mqtt_broker_url")
    val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
    val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")

    // Wake word
    val WAKE_WORD = stringPreferencesKey("wake_word")
    val HOTWORD_ENABLED = booleanPreferencesKey("hotword_enabled")

    // Setup
    val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
}
