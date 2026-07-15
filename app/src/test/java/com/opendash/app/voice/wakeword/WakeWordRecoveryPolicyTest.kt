package com.opendash.app.voice.wakeword

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WakeWordRecoveryPolicyTest {

    private val policy = WakeWordRecoveryPolicy()

    @Test
    fun `failed health is recoverable within attempt budget`() {
        assertThat(policy.shouldRecover(WakeWordHealth.Failed("boom"), attempt = 0)).isTrue()
        assertThat(policy.shouldRecover(WakeWordHealth.Failed("boom"), attempt = 2)).isTrue()
    }

    @Test
    fun `failed health is not recoverable after max attempts`() {
        assertThat(
            policy.shouldRecover(WakeWordHealth.Failed("boom"), attempt = WakeWordRecoveryPolicy.MAX_ATTEMPTS)
        ).isFalse()
    }

    @Test
    fun `listening and paused are not recoverable`() {
        assertThat(policy.shouldRecover(WakeWordHealth.Listening, attempt = 0)).isFalse()
        assertThat(policy.shouldRecover(WakeWordHealth.Paused, attempt = 0)).isFalse()
        assertThat(policy.shouldRecover(WakeWordHealth.Unavailable("off"), attempt = 0)).isFalse()
    }

    @Test
    fun `backoff is bounded and non-decreasing`() {
        val first = policy.backoffMs(0)
        val second = policy.backoffMs(1)
        val last = policy.backoffMs(10)
        assertThat(first).isGreaterThan(0)
        assertThat(second).isAtLeast(first)
        assertThat(last).isAtMost(WakeWordRecoveryPolicy.MAX_BACKOFF_MS)
    }
}
