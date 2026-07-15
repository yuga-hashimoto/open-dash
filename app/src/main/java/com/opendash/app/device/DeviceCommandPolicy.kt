package com.opendash.app.device

object DeviceCommandPolicy {
    const val CONFIRMATION_REQUIRED_PREFIX = "Confirmation required:"
    const val CONFIRMATION_TOKEN_KEY = "confirmation_token"

    fun requiresConfirmation(action: String): Boolean = action == "lock" || action == "unlock"

    fun isConfirmed(parameters: Map<String, Any?>): Boolean = parameters["confirmed"] == true

    fun confirmationMessage(deviceName: String, action: String): String =
        "$CONFIRMATION_REQUIRED_PREFIX say yes to confirm $action on $deviceName."
}
