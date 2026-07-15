package com.opendash.app.device

import com.opendash.app.device.model.CommandResult
import com.opendash.app.device.model.Device
import com.opendash.app.device.model.DeviceCapability
import com.opendash.app.device.model.DeviceCommand
import com.opendash.app.device.model.DeviceType
import com.opendash.app.device.model.Room
import com.opendash.app.device.provider.DeviceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class DeviceManager(
    private val providers: Set<DeviceProvider>,
    private val refreshIntervalMs: Long = 30000L
) {
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private val stateJobs = mutableListOf<Job>()
    private var started = false
    private data class PendingConfirmation(val action: String, val token: String, val expiresAt: Long)
    @Volatile private var pendingConfirmation: PendingConfirmation? = null

    suspend fun start() {
        if (started) return
        started = true
        refreshAll()
        refreshJob = scope.launch {
            while (isActive) {
                delay(refreshIntervalMs)
                refreshAll()
            }
        }
        // Merge state change flows from all providers
        providers.forEach { provider ->
            stateJobs += scope.launch {
                provider.stateChanges().collect { state ->
                    val current = _devices.value.toMutableMap()
                    val existing = current[state.deviceId]
                    if (existing != null) {
                        current[state.deviceId] = existing.copy(state = state)
                        _devices.value = current
                    }
                }
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        stateJobs.forEach(Job::cancel)
        stateJobs.clear()
        started = false
    }

    suspend fun refreshAll() {
        val allDevices = mutableMapOf<String, Device>()
        for (provider in providers) {
            try {
                val devices = provider.discover()
                if (provider.isAvailable()) {
                    devices.forEach { allDevices[it.id] = it }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh devices from ${provider.id}")
            }
        }
        _devices.value = allDevices
        Timber.d("Device cache refreshed: ${allDevices.size} devices from ${providers.size} providers")
    }

    suspend fun executeCommand(command: DeviceCommand): CommandResult {
        val device = _devices.value[command.deviceId]
            ?: return CommandResult(false, "Device not found: ${command.deviceId}")
        validateCommand(device, command)?.let { message ->
            val token = if (DeviceCommandPolicy.requiresConfirmation(command.action) &&
                !DeviceCommandPolicy.isConfirmed(command.parameters)
            ) issueConfirmationToken(command.action) else null
            return CommandResult(false, message, confirmationToken = token)
        }
        val provider = providers.find { it.id == device.providerId }
            ?: return CommandResult(false, "Provider not found: ${device.providerId}")
        return try {
            val result = provider.executeCommand(command)
            if (!result.success) return result
            val readBack = runCatching { provider.getDeviceState(command.deviceId) }.getOrNull()
            result.copy(
                updatedState = readBack ?: result.updatedState,
                confirmed = readBack != null,
                message = result.message ?: if (readBack == null) {
                    "Command accepted; state not confirmed"
                } else null
            )
        } catch (e: Exception) {
            Timber.e(e, "Command execution failed")
            CommandResult(false, e.message)
        }
    }

    fun getDevice(deviceId: String): Device? = _devices.value[deviceId]

    fun getDevicesByType(type: DeviceType): List<Device> =
        _devices.value.values.filter { it.type == type }

    fun getDevicesByRoom(room: String): List<Device> =
        _devices.value.values.filter { it.room.equals(room, ignoreCase = true) }

    fun getRooms(): List<Room> =
        _devices.value.values
            .mapNotNull { it.room }
            .distinct()
            .map { Room(id = it.lowercase().replace(" ", "_"), name = it) }

    private fun validateCommand(device: Device, command: DeviceCommand): String? {
        val requiredCapability = when (command.action) {
            "turn_on", "turn_off", "toggle" -> DeviceCapability.ON_OFF
            "set_brightness" -> DeviceCapability.BRIGHTNESS
            "set_temperature" -> DeviceCapability.TEMPERATURE_SET
            "volume_set" -> DeviceCapability.VOLUME
            "media_play", "media_pause" -> DeviceCapability.PLAY_PAUSE
            "media_next_track", "media_previous_track" -> DeviceCapability.MEDIA_NEXT_PREV
            "open_cover", "close_cover", "set_position" -> DeviceCapability.POSITION
            "lock", "unlock" -> DeviceCapability.LOCK_UNLOCK
            "shuffle_set", "repeat_set", "select_source" -> null
            else -> return "Unsupported device action: ${command.action}"
        }

        if (requiredCapability != null && requiredCapability !in device.capabilities) {
            return "Device ${device.name} lacks capability ${requiredCapability.name}"
        }
        if (DeviceCommandPolicy.requiresConfirmation(command.action) &&
            !DeviceCommandPolicy.isConfirmed(command.parameters)
        ) {
            return DeviceCommandPolicy.confirmationMessage(device.name, command.action)
        }
        if (DeviceCommandPolicy.requiresConfirmation(command.action) &&
            !isValidConfirmationToken(command.action, command.parameters)
        ) {
            return DeviceCommandPolicy.confirmationMessage(device.name, command.action)
        }
        if (command.action in setOf("shuffle_set", "repeat_set", "select_source") &&
            device.type != DeviceType.MEDIA_PLAYER
        ) {
            return "Device ${device.name} does not support media control"
        }
        if (command.action in setOf("turn_on", "turn_off") &&
            command.parameters.containsKey("brightness") &&
            DeviceCapability.BRIGHTNESS !in device.capabilities
        ) {
            return "Device ${device.name} lacks capability ${DeviceCapability.BRIGHTNESS.name}"
        }
        return null
    }

    private fun issueConfirmationToken(action: String): String {
        val current = pendingConfirmation
        if (current != null && current.action == action && current.expiresAt > System.currentTimeMillis()) {
            return current.token
        }
        val issued = PendingConfirmation(
            action = action,
            token = UUID.randomUUID().toString(),
            expiresAt = System.currentTimeMillis() + 30_000L
        )
        pendingConfirmation = issued
        return issued.token
    }

    private fun isValidConfirmationToken(action: String, parameters: Map<String, Any?>): Boolean {
        val token = parameters[DeviceCommandPolicy.CONFIRMATION_TOKEN_KEY] as? String
        val pending = pendingConfirmation
        return token != null && pending != null &&
            pending.action == action && pending.token == token &&
            pending.expiresAt > System.currentTimeMillis()
    }
}
