package com.opendash.app.device.provider.matter

import com.google.common.truth.Truth.assertThat
import com.opendash.app.device.model.DeviceCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MatterCommandDispatcherTest {

    @Test
    fun `unavailable dispatcher is an explicit integration gate`() = runTest {
        val dispatcher = UnavailableMatterCommandDispatcher

        assertThat(dispatcher.isAvailable).isFalse()

        val result = dispatcher.dispatch(
            DeviceCommand(deviceId = "node-1", action = "turn_on")
        )

        assertThat(result).isInstanceOf(MatterDispatchResult.Unsupported::class.java)
        val unsupported = result as MatterDispatchResult.Unsupported
        assertThat(unsupported.message).contains("unavailable")
    }

    @Test
    fun `fake dispatcher records commands and returns configured outcomes`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher(
            result = MatterDispatchResult.Confirmed
        )

        assertThat(dispatcher.isAvailable).isTrue()

        val command = DeviceCommand("node-1", "turn_off")
        val result = dispatcher.dispatch(command)

        assertThat(result).isEqualTo(MatterDispatchResult.Confirmed)
        assertThat(dispatcher.dispatched).containsExactly(command)
    }

    @Test
    fun `fake dispatcher can report native failure without claiming success`() = runTest {
        val dispatcher = FakeMatterCommandDispatcher(
            result = MatterDispatchResult.Failed("timeout")
        )

        val result = dispatcher.dispatch(DeviceCommand("node-1", "turn_on"))

        assertThat(result).isEqualTo(MatterDispatchResult.Failed("timeout"))
        assertThat(result).isNotInstanceOf(MatterDispatchResult.Confirmed::class.java)
    }
}

/**
 * JVM-only test double for native Matter cluster control.
 * Never pretends a real SDK is present; outcomes are explicit and configurable.
 */
class FakeMatterCommandDispatcher(
    private val result: MatterDispatchResult = MatterDispatchResult.Confirmed,
    override val isAvailable: Boolean = true
) : MatterCommandDispatcher {

    val dispatched = mutableListOf<DeviceCommand>()

    override suspend fun dispatch(command: DeviceCommand): MatterDispatchResult {
        dispatched += command
        return result
    }
}
