package com.opendash.app.device.model

data class CommandResult(
    val success: Boolean,
    val message: String? = null,
    val updatedState: DeviceState? = null,
    val confirmed: Boolean = true,
    /** Opaque, short-lived token issued only after a sensitive command is refused. */
    val confirmationToken: String? = null
)
