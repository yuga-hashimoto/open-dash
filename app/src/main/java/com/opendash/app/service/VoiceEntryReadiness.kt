package com.opendash.app.service

import com.opendash.app.assistant.provider.ProviderReadiness

/**
 * Pure gate for voice entry points (wake word, mic Talk, QS tile).
 * Callers must await provider init and device-manager start before deciding.
 */
object VoiceEntryReadiness {

    sealed class Decision {
        data object Proceed : Decision()
        data object Wait : Decision()
        data class Degraded(val reason: String) : Decision()
    }

    fun decide(provider: ProviderReadiness, deviceReady: Boolean): Decision {
        if (!deviceReady) return Decision.Wait
        return when (provider) {
            is ProviderReadiness.Starting -> Decision.Wait
            is ProviderReadiness.Ready -> Decision.Proceed
            is ProviderReadiness.Degraded -> Decision.Degraded(provider.reason)
        }
    }
}
