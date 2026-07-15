package com.opendash.app.voice.pipeline

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolResult
import com.opendash.app.voice.fastpath.FastPathMatch
import org.junit.jupiter.api.Test

class FastPathResponsePolicyTest {

    private val match = FastPathMatch(
        toolName = "turn_on",
        spokenConfirmation = "Done."
    )

    @Test
    fun `failed tool result cannot use the success confirmation`() {
        val result = ToolResult(
            callId = "call-1",
            success = false,
            data = "",
            error = "device unavailable"
        )

        assertThat(canUseFastPathSpokenConfirmation(match, result)).isFalse()
    }

    @Test
    fun `successful tool result can use the confirmation`() {
        val result = ToolResult(
            callId = "call-1",
            success = true,
            data = "{}"
        )

        assertThat(canUseFastPathSpokenConfirmation(match, result)).isTrue()
    }

    @Test
    fun `accepted but unconfirmed result cannot use the confirmation`() {
        val result = ToolResult(
            callId = "call-1",
            success = true,
            data = "accepted",
            error = "state not confirmed",
            confirmed = false
        )

        assertThat(canUseFastPathSpokenConfirmation(match, result)).isFalse()
    }
}
