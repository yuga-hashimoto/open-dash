package com.opendash.app.assistant.provider

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RemoteDataPolicyTest {

    @Test
    fun `local provider is always allowed`() {
        assertThat(
            RemoteDataDecision.decide(isLocal = true, localOnly = true, disclosureAccepted = false)
        ).isEqualTo(RemoteDataDecision.Allow)
    }

    @Test
    fun `local only blocks a remote provider`() {
        assertThat(
            RemoteDataDecision.decide(isLocal = false, localOnly = true, disclosureAccepted = true)
        ).isEqualTo(RemoteDataDecision.BlockedByLocalOnly)
    }

    @Test
    fun `first remote use requires disclosure`() {
        assertThat(
            RemoteDataDecision.decide(isLocal = false, localOnly = false, disclosureAccepted = false)
        ).isEqualTo(RemoteDataDecision.RequiresDisclosure)
    }

    @Test
    fun `accepted remote use is allowed`() {
        assertThat(
            RemoteDataDecision.decide(isLocal = false, localOnly = false, disclosureAccepted = true)
        ).isEqualTo(RemoteDataDecision.Allow)
    }
}
