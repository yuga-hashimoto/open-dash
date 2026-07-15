package com.opendash.app.device.settings

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.device.provider.mqtt.MqttConfig
import com.opendash.app.device.provider.switchbot.SwitchBotConfig
import com.opendash.app.homeassistant.client.HomeAssistantConfig
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceSettingsSnapshot(
    val homeAssistant: HomeAssistantConfig,
    val switchBot: SwitchBotConfig,
    val mqtt: MqttConfig
)

@Singleton
class DeviceSettingsRepository @Inject constructor(
    private val preferences: AppPreferences,
    private val securePreferences: SecurePreferences
) {
    suspend fun snapshot(): DeviceSettingsSnapshot {
        val homeAssistantUrl = preferences.observe(PreferenceKeys.HA_BASE_URL)
            .first()
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
        val mqttBrokerUrl = preferences.observe(PreferenceKeys.MQTT_BROKER_URL)
            .first()
            ?.trim()
            .orEmpty()
        val mqttUsername = preferences.observe(PreferenceKeys.MQTT_USERNAME)
            .first()
            ?.trim()
            .orEmpty()

        return DeviceSettingsSnapshot(
            homeAssistant = HomeAssistantConfig(
                baseUrl = homeAssistantUrl,
                token = securePreferences.getString(SecurePreferences.KEY_HA_TOKEN).trim()
            ),
            switchBot = SwitchBotConfig(
                token = securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_TOKEN).trim(),
                secret = securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_SECRET).trim()
            ),
            mqtt = MqttConfig(
                brokerUrl = mqttBrokerUrl,
                username = mqttUsername,
                password = securePreferences.getString(SecurePreferences.KEY_MQTT_PASSWORD)
            )
        )
    }
}
