package com.opendash.app.homeassistant.client

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.device.settings.DeviceSettingsRepository
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HomeAssistantRestClientRuntimeConfigTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HomeAssistantRestClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val preferences = mockk<AppPreferences>()
        val securePreferences = mockk<SecurePreferences>()
        every { preferences.observe(PreferenceKeys.HA_BASE_URL) } returns
            flowOf(server.url("/").toString().trimEnd('/'))
        every { preferences.observe(PreferenceKeys.MQTT_BROKER_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.MQTT_USERNAME) } returns flowOf(null)
        every { securePreferences.getString(SecurePreferences.KEY_HA_TOKEN) } returns "runtime-token"
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_TOKEN) } returns ""
        every { securePreferences.getString(SecurePreferences.KEY_SWITCHBOT_SECRET) } returns ""
        every { securePreferences.getString(SecurePreferences.KEY_MQTT_PASSWORD) } returns ""

        client = HomeAssistantRestClient(
            OkHttpClient(),
            Moshi.Builder().build(),
            DeviceSettingsRepository(preferences, securePreferences)
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getStates uses the current persisted url and token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        client.getStates()

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/states")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer runtime-token")
    }
}
