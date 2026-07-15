package com.opendash.app.device.provider.matter

import com.opendash.app.device.model.DeviceCommand

/**
 * Injected boundary for native Matter cluster command dispatch.
 *
 * The default JVM/build has no Matter cluster SDK dependency. Production
 * integrations supply a real dispatcher; unit tests use [FakeMatterCommandDispatcher]
 * in test sources. Never reflectively invent a successful native path.
 */
interface MatterCommandDispatcher {
    /** True only when a native Matter cluster runtime is actually present. */
    val isAvailable: Boolean

    suspend fun dispatch(command: DeviceCommand): MatterDispatchResult
}

/**
 * Explicit outcomes from the Matter dispatch boundary.
 * Provider maps these to [com.opendash.app.device.model.CommandResult].
 */
sealed class MatterDispatchResult {
    /** Command applied and state may be mirrored locally as confirmed. */
    data object Confirmed : MatterDispatchResult()

    /** Command accepted by transport but device state was not confirmed. */
    data object AcceptedUnconfirmed : MatterDispatchResult()

    /** Capability/action/SDK not supported; fail closed. */
    data class Unsupported(val message: String) : MatterDispatchResult()

    /** Native/runtime failure; fail closed with no local state mutation. */
    data class Failed(val message: String) : MatterDispatchResult()
}

/**
 * Default dispatcher when no Matter cluster SDK/runtime is wired.
 * Always reports unavailable and returns a precise unsupported result.
 */
object UnavailableMatterCommandDispatcher : MatterCommandDispatcher {
    override val isAvailable: Boolean = false

    override suspend fun dispatch(command: DeviceCommand): MatterDispatchResult =
        MatterDispatchResult.Unsupported(
            "Matter native cluster SDK is unavailable; real device control requires " +
                "device-side Google Home/Matter runtime and physical acceptance"
        )
}
