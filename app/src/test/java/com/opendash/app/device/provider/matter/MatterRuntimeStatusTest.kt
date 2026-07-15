package com.opendash.app.device.provider.matter

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MatterRuntimeStatusTest {
    @Test
    fun `missing native dispatcher is never ready`() {
        assertThat(
            MatterRuntimeStatus(false, 0).readiness
        ).isEqualTo(MatterRuntimeStatus.Readiness.NativeRuntimeUnavailable)
    }

    @Test
    fun `available dispatcher still reports physical gate until verified`() {
        assertThat(
            MatterRuntimeStatus(true, 1).readiness
        ).isEqualTo(MatterRuntimeStatus.Readiness.ReadyButPhysicalAcceptanceOpen)
    }

    @Test
    fun `physical verification is explicit`() {
        assertThat(
            MatterRuntimeStatus(true, 1, physicalAcceptanceVerified = true).readiness
        ).isEqualTo(MatterRuntimeStatus.Readiness.ReadyForDispatch)
    }
}
