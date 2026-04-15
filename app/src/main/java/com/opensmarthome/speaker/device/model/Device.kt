package com.opensmarthome.speaker.device.model

data class Device(
    val id: String,
    val providerId: String,
    val name: String,
    val room: String? = null,
    val type: DeviceType,
    val capabilities: Set<DeviceCapability>,
    val state: DeviceState
)
