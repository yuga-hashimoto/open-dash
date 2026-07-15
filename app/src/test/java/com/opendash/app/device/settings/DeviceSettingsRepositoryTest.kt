package com.opendash.app.device.settings

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceSettingsRepositoryTest {

    private lateinit var preferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var repository: DeviceSettingsRepository

    @BeforeEach
    fun setUp() {
        preferences = mockk()
        securePreferences = mockk()
        every { preferences.observe(PreferenceKeys.HA_BASE_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.MQTT_BROKER_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.MQTT_USERNAME) } returns flowOf(null)
        every { securePreferences.getString(SecurePreferences.KEY_HA_TOKEN) } returns ""
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_TOKEN) } returns ""
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_SECRET) } returns ""
        every { securePreferences.getString(SecurePreferences.KEY_MQTT_PASSWORD) } returns ""
        repository = DeviceSettingsRepository(preferences, securePreferences)
    }

    @Test
    fun `snapshot uses empty values when no device settings are saved`() = runTest {
        val snapshot = repository.snapshot()

        assertThat(snapshot.homeAssistant.baseUrl).isEmpty()
        assertThat(snapshot.homeAssistant.token).isEmpty()
        assertThat(snapshot.switchBot.token).isEmpty()
        assertThat(snapshot.mqtt.brokerUrl).isEmpty()
    }

    @Test
    fun `snapshot combines plaintext and secure provider settings`() = runTest {
        every { preferences.observe(PreferenceKeys.HA_BASE_URL) } returns flowOf(" http://ha.local:8123/ ")
        every { preferences.observe(PreferenceKeys.MQTT_BROKER_URL) } returns flowOf("tcp://broker.local:1883")
        every { preferences.observe(PreferenceKeys.MQTT_USERNAME) } returns flowOf("dash")
        every { securePreferences.getString(SecurePreferences.KEY_HA_TOKEN) } returns "ha-token"
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_TOKEN) } returns "switch-token"
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_SECRET) } returns "switch-secret"
        every { securePreferences.getString(SecurePreferences.KEY_MQTT_PASSWORD) } returns "mqtt-password"

        val snapshot = repository.snapshot()

        assertThat(snapshot.homeAssistant.baseUrl).isEqualTo("http://ha.local:8123")
        assertThat(snapshot.homeAssistant.token).isEqualTo("ha-token")
        assertThat(snapshot.switchBot.token).isEqualTo("switch-token")
        assertThat(snapshot.switchBot.secret).isEqualTo("switch-secret")
        assertThat(snapshot.mqtt.brokerUrl).isEqualTo("tcp://broker.local:1883")
        assertThat(snapshot.mqtt.username).isEqualTo("dash")
        assertThat(snapshot.mqtt.password).isEqualTo("mqtt-password")
    }
}
