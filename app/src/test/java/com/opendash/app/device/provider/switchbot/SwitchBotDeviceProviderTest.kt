package com.opendash.app.device.provider.switchbot

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SwitchBotDeviceProviderTest {

    @Test
    fun `executeCommand strips the provider prefix before calling the API`() = runTest {
        val apiClient = mockk<SwitchBotApiClient>()
        val provider = SwitchBotDeviceProvider(apiClient)
        coEvery { apiClient.sendCommand(any(), any(), any(), any()) } returns true

        val result = provider.executeCommand(
            com.opendash.app.device.model.DeviceCommand("switchbot_raw-id", "turn_on")
        )

        assertThat(result.success).isTrue()
        coVerify { apiClient.sendCommand("raw-id", "turnOn", "default", "command") }
    }
}
