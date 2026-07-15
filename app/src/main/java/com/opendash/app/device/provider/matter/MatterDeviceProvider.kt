package com.opendash.app.device.provider.matter

import com.opendash.app.device.model.CommandResult
import com.opendash.app.device.model.Device
import com.opendash.app.device.model.DeviceCapability
import com.opendash.app.device.model.DeviceCommand
import com.opendash.app.device.model.DeviceState
import com.opendash.app.device.model.DeviceType
import com.opendash.app.device.provider.DeviceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import timber.log.Timber

/**
 * Matter device provider using Android's Matter commissioning API.
 *
 * Requires Google Play Services (com.google.android.gms.home.matter).
 * Gracefully degrades on devices without GMS.
 *
 * Cluster command delivery is delegated to an injected [MatterCommandDispatcher].
 * The default build has no native cluster SDK; the unavailable dispatcher fails
 * closed. In-memory state is updated only after the dispatcher reports success.
 *
 * Matter clusters supported (when a real dispatcher is wired):
 * - On/Off (0x0006)
 * - Level Control (0x0008) - brightness
 * - Color Control (0x0300)
 * - Thermostat (0x0201)
 * - Door Lock (0x0101)
 */
class MatterDeviceProvider(
    private val commandDispatcher: MatterCommandDispatcher = UnavailableMatterCommandDispatcher
) : DeviceProvider {

    override val id: String = "matter"
    override val displayName: String = "Matter"

    private val commissionedDevices = mutableMapOf<String, Device>()

    /** Integration gate: true only when a native cluster dispatcher is available. */
    val isClusterDispatchAvailable: Boolean
        get() = commandDispatcher.isAvailable

    fun runtimeStatus(physicalAcceptanceVerified: Boolean = false): MatterRuntimeStatus =
        MatterRuntimeStatus(
            nativeDispatcherAvailable = commandDispatcher.isAvailable,
            commissionedDeviceCount = commissionedDevices.size,
            physicalAcceptanceVerified = physicalAcceptanceVerified,
        )

    override suspend fun discover(): List<Device> {
        // Matter commissioning is initiated by user via QR code scan
        // Discovery returns already commissioned devices
        return commissionedDevices.values.toList()
    }

    override suspend fun getDevices(): List<Device> = commissionedDevices.values.toList()

    override suspend fun getDeviceState(deviceId: String): DeviceState {
        val device = commissionedDevices[deviceId]
            ?: throw IllegalArgumentException("Device not found: $deviceId")
        return device.state
    }

    override suspend fun executeCommand(command: DeviceCommand): CommandResult {
        val device = commissionedDevices[command.deviceId]
            ?: return CommandResult(success = false, message = "Device not found", confirmed = false)

        val capabilityError = validateCapability(device, command)
        if (capabilityError != null) {
            return CommandResult(success = false, message = capabilityError, confirmed = false)
        }

        if (!commandDispatcher.isAvailable) {
            Timber.w("Matter command rejected: native cluster dispatcher unavailable")
            return CommandResult(
                success = false,
                message = "Matter native cluster SDK is unavailable",
                confirmed = false
            )
        }

        val dispatchResult = try {
            commandDispatcher.dispatch(command)
        } catch (e: Exception) {
            Timber.e(e, "Matter dispatcher threw for ${command.deviceId}/${command.action}")
            return CommandResult(
                success = false,
                message = e.message ?: "Matter dispatch failed",
                confirmed = false
            )
        }

        return when (dispatchResult) {
            is MatterDispatchResult.Confirmed -> {
                val updated = applyLocalState(device, command)
                CommandResult(
                    success = true,
                    message = null,
                    updatedState = updated,
                    confirmed = true
                )
            }
            is MatterDispatchResult.AcceptedUnconfirmed -> {
                val updated = applyLocalState(device, command)
                CommandResult(
                    success = true,
                    message = "Command accepted; state not confirmed",
                    updatedState = updated,
                    confirmed = false
                )
            }
            is MatterDispatchResult.Unsupported -> {
                CommandResult(
                    success = false,
                    message = dispatchResult.message,
                    confirmed = false
                )
            }
            is MatterDispatchResult.Failed -> {
                CommandResult(
                    success = false,
                    message = dispatchResult.message,
                    confirmed = false
                )
            }
        }
    }

    override fun stateChanges(): Flow<DeviceState> = emptyFlow()

    override suspend fun isAvailable(): Boolean {
        return try {
            Class.forName("com.google.android.gms.home.matter.Matter")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    fun addCommissionedDevice(
        deviceId: String,
        name: String,
        type: DeviceType,
        capabilities: Set<DeviceCapability>
    ) {
        commissionedDevices[deviceId] = Device(
            id = deviceId,
            providerId = id,
            name = name,
            type = type,
            capabilities = capabilities,
            state = DeviceState(deviceId = deviceId, isOn = false)
        )
    }

    private fun validateCapability(device: Device, command: DeviceCommand): String? {
        val required = requiredCapability(command.action)
            ?: return "Unsupported Matter action: ${command.action}"
        if (required !in device.capabilities) {
            return "Unsupported: device ${device.name} lacks capability ${required.name}"
        }
        return null
    }

    private fun requiredCapability(action: String): DeviceCapability? = when (action) {
        "turn_on", "turn_off", "toggle" -> DeviceCapability.ON_OFF
        "set_brightness" -> DeviceCapability.BRIGHTNESS
        "set_temperature" -> DeviceCapability.TEMPERATURE_SET
        "lock", "unlock" -> DeviceCapability.LOCK_UNLOCK
        else -> null
    }

    private fun applyLocalState(device: Device, command: DeviceCommand): DeviceState {
        val current = device.state
        val next = when (command.action) {
            "turn_on" -> current.copy(isOn = true)
            "turn_off" -> current.copy(isOn = false)
            "toggle" -> current.copy(isOn = !(current.isOn ?: false))
            "set_brightness" -> {
                val brightness = (command.parameters["brightness"] as? Number)?.toFloat()
                current.copy(
                    isOn = true,
                    brightness = brightness ?: current.brightness
                )
            }
            "set_temperature" -> {
                val temperature = (command.parameters["temperature"] as? Number)?.toFloat()
                current.copy(temperature = temperature ?: current.temperature)
            }
            "lock" -> current.copy(attributes = current.attributes + ("locked" to true))
            "unlock" -> current.copy(attributes = current.attributes + ("locked" to false))
            else -> current
        }
        commissionedDevices[device.id] = device.copy(state = next)
        return next
    }
}
