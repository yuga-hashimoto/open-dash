package com.opendash.app.service

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.ProviderReadiness
import org.junit.jupiter.api.Test

class VoiceEntryReadinessTest {

    @Test
    fun `ready provider and ready device proceeds`() {
        val decision = VoiceEntryReadiness.decide(
            provider = ProviderReadiness.Ready,
            deviceReady = true
        )
        assertThat(decision).isEqualTo(VoiceEntryReadiness.Decision.Proceed)
    }

    @Test
    fun `degraded provider yields spoken degraded decision`() {
        val decision = VoiceEntryReadiness.decide(
            provider = ProviderReadiness.Degraded("No AI model is ready yet."),
            deviceReady = true
        )
        assertThat(decision).isEqualTo(
            VoiceEntryReadiness.Decision.Degraded("No AI model is ready yet.")
        )
    }

    @Test
    fun `starting provider is not ready even if devices are`() {
        val decision = VoiceEntryReadiness.decide(
            provider = ProviderReadiness.Starting,
            deviceReady = true
        )
        assertThat(decision).isEqualTo(VoiceEntryReadiness.Decision.Wait)
    }

    @Test
    fun `device not ready waits even if provider is ready`() {
        val decision = VoiceEntryReadiness.decide(
            provider = ProviderReadiness.Ready,
            deviceReady = false
        )
        assertThat(decision).isEqualTo(VoiceEntryReadiness.Decision.Wait)
    }
}
