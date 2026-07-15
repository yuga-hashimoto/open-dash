package com.opendash.app.assistant.provider

/**
 * Coarse readiness state for the assistant provider registry.
 *
 * Exposed by [ProviderManager.readiness] so that application/service-owned
 * lifecycle code (OpenDashApp, VoiceService) can await a usable provider
 * before accepting a voice turn, instead of discovering the absence at the
 * point [ConversationRouter.resolveProvider] throws.
 *
 * State transitions:
 *  - [Starting] → [Ready] or [Degraded] once [ProviderManager.initialize] finishes.
 *  - [Ready] → [Degraded] if every registered provider later becomes unavailable.
 *  - [Degraded] → [Ready] after a successful re-initialization.
 */
sealed class ProviderReadiness {
    /** Initial state — initialization has not completed yet. */
    data object Starting : ProviderReadiness()

    /** At least one provider is registered and reports available. */
    data object Ready : ProviderReadiness()

    /** Initialization completed but no provider is usable. [reason] is user-facing. */
    data class Degraded(val reason: String) : ProviderReadiness()
}
