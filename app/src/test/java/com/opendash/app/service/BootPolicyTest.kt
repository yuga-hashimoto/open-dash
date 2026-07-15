package com.opendash.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BootPolicyTest {

    @Test
    fun `boot completed does not start microphone foreground service`() {
        val decision = BootPolicy.onBootCompleted()
        assertThat(decision.startMicrophoneForegroundService).isFalse()
    }

    @Test
    fun `boot completed still reschedules alarms reminders and routines`() {
        val decision = BootPolicy.onBootCompleted()
        assertThat(decision.rescheduleAlarmsRemindersRoutines).isTrue()
    }
}
