package com.opendash.app.device.provider.matter

/** Truthful readiness summary for Matter integration and physical acceptance. */
data class MatterRuntimeStatus(
    val nativeDispatcherAvailable: Boolean,
    val commissionedDeviceCount: Int,
    val physicalAcceptanceVerified: Boolean = false,
) {
    enum class Readiness {
        NativeRuntimeUnavailable,
        ReadyForDispatch,
        ReadyButPhysicalAcceptanceOpen,
    }

    val readiness: Readiness
        get() = when {
            !nativeDispatcherAvailable -> Readiness.NativeRuntimeUnavailable
            physicalAcceptanceVerified -> Readiness.ReadyForDispatch
            else -> Readiness.ReadyButPhysicalAcceptanceOpen
        }
}
