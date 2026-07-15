package com.opendash.app.device.provider.matter

import com.google.common.truth.Truth.assertThat
import com.opendash.app.device.model.DeviceCapability
import com.opendash.app.device.model.DeviceCommand
import com.opendash.app.device.model.DeviceType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MatterDeviceProviderTest {

    @Test
    fun `absent dispatcher never claims success and leaves state unchanged`() = runTest {
        val provider = MatterDeviceProvider()
        provider.addCommissionedDevice(
            deviceId = "matter-light",
            name = "Matter light",
            type = DeviceType.LIGHT,
            capabilities = setOf(DeviceCapability.ON_OFF)
        )

        val result = provider.executeCommand(DeviceCommand("matter-light", "turn_on"))

        assertThat(result.success).isFalse()
        assertThat(result.confirmed).isFalse()
        assertThat(result.message).contains("unavailable")
        assertThat(provider.getDeviceState("matter-light").isOn).isFalse()
    }

    @Test
    fun `successful fake On Off dispatch updates in-memory state`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher()
        val provider = MatterDeviceProvider(dispatcher)
        provider.addCommissionedDevice(
            deviceId = "matter-light",
            name = "Matter light",
            type = DeviceType.LIGHT,
            capabilities = setOf(DeviceCapability.ON_OFF)
        )

        val result = provider.executeCommand(DeviceCommand("matter-light", "turn_on"))

        assertThat(result.success).isTrue()
        assertThat(result.confirmed).isTrue()
        assertThat(result.updatedState?.isOn).isTrue()
        assertThat(provider.getDeviceState("matter-light").isOn).isTrue()
        assertThat(dispatcher.dispatched).hasSize(1)
        assertThat(dispatcher.dispatched[0].action).isEqualTo("turn_on")
    }

    @Test
    fun `dispatcher failure leaves state unchanged`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher(
            result = MatterDispatchResult.Failed("native cluster write failed")
        )
        val provider = MatterDeviceProvider(dispatcher)
        provider.addCommissionedDevice(
            deviceId = "matter-light",
            name = "Matter light",
            type = DeviceType.LIGHT,
            capabilities = setOf(DeviceCapability.ON_OFF)
        )

        val result = provider.executeCommand(DeviceCommand("matter-light", "turn_on"))

        assertThat(result.success).isFalse()
        assertThat(result.confirmed).isFalse()
        assertThat(result.message).contains("native cluster write failed")
        assertThat(provider.getDeviceState("matter-light").isOn).isFalse()
        assertThat(result.updatedState).isNull()
    }

    @Test
    fun `unsupported action returns stable error without mutating state`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher()
        val provider = MatterDeviceProvider(dispatcher)
        provider.addCommissionedDevice(
            deviceId = "matter-switch",
            name = "Matter switch",
            type = DeviceType.SWITCH,
            capabilities = setOf(DeviceCapability.ON_OFF)
        )

        val result = provider.executeCommand(
            DeviceCommand("matter-switch", "set_brightness", mapOf("brightness" to 0.5f))
        )

        assertThat(result.success).isFalse()
        assertThat(result.confirmed).isFalse()
        assertThat(result.message).contains("Unsupported")
        assertThat(provider.getDeviceState("matter-switch").isOn).isFalse()
        assertThat(dispatcher.dispatched).isEmpty()
    }

    @Test
    fun `accepted but unconfirmed success does not claim confirmed state`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher(
            result = MatterDispatchResult.AcceptedUnconfirmed
        )
        val provider = MatterDeviceProvider(dispatcher)
        provider.addCommissionedDevice(
            deviceId = "matter-light",
            name = "Matter light",
            type = DeviceType.LIGHT,
            capabilities = setOf(DeviceCapability.ON_OFF)
        )

        val result = provider.executeCommand(DeviceCommand("matter-light", "turn_on"))

        assertThat(result.success).isTrue()
        assertThat(result.confirmed).isFalse()
        assertThat(result.message).contains("not confirmed")
        // Accepted still updates the local mirror; confirmed stays false.
        assertThat(provider.getDeviceState("matter-light").isOn).isTrue()
        assertThat(result.updatedState?.isOn).isTrue()
    }
}
