package com.opensmarthome.speaker.device.provider

import com.opensmarthome.speaker.device.model.CommandResult
import com.opensmarthome.speaker.device.model.Device
import com.opensmarthome.speaker.device.model.DeviceCommand
import com.opensmarthome.speaker.device.model.DeviceState
import kotlinx.coroutines.flow.Flow

interface DeviceProvider {
    val id: String
    val displayName: String

    suspend fun discover(): List<Device>
    suspend fun getDevices(): List<Device>
    suspend fun getDeviceState(deviceId: String): DeviceState
    suspend fun executeCommand(command: DeviceCommand): CommandResult
    fun stateChanges(): Flow<DeviceState>
    suspend fun isAvailable(): Boolean
}
