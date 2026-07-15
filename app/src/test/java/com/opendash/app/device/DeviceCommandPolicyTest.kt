package com.opendash.app.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeviceCommandPolicyTest {

    @Test
    fun `lock and unlock require explicit confirmation`() {
        assertThat(DeviceCommandPolicy.requiresConfirmation("lock")).isTrue()
        assertThat(DeviceCommandPolicy.requiresConfirmation("unlock")).isTrue()
    }

    @Test
    fun `ordinary device actions do not require confirmation`() {
        assertThat(DeviceCommandPolicy.requiresConfirmation("turn_on")).isFalse()
    }

    @Test
    fun `confirmation requires a boolean true parameter`() {
        assertThat(DeviceCommandPolicy.isConfirmed(emptyMap())).isFalse()
        assertThat(DeviceCommandPolicy.isConfirmed(mapOf("confirmed" to false))).isFalse()
        assertThat(DeviceCommandPolicy.isConfirmed(mapOf("confirmed" to true))).isTrue()
    }
}
