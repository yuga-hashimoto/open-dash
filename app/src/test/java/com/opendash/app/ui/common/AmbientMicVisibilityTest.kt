package com.opendash.app.ui.common

import com.google.common.truth.Truth.assertThat
import com.opendash.app.voice.wakeword.WakeWordHealth
import org.junit.jupiter.api.Test

class AmbientMicVisibilityTest {

    @Test
    fun `hide mic when hotword is enabled and listening`() {
        assertThat(
            AmbientMicVisibility.shouldShowTalkAction(
                hotwordEnabled = true,
                health = WakeWordHealth.Listening
            )
        ).isFalse()
    }

    @Test
    fun `show mic when hotword preference is disabled`() {
        assertThat(
            AmbientMicVisibility.shouldShowTalkAction(
                hotwordEnabled = false,
                health = WakeWordHealth.Unavailable("disabled")
            )
        ).isTrue()
    }

    @Test
    fun `show mic when detector failed`() {
        assertThat(
            AmbientMicVisibility.shouldShowTalkAction(
                hotwordEnabled = true,
                health = WakeWordHealth.Failed("AudioRecord dead")
            )
        ).isTrue()
    }

    @Test
    fun `show mic when detector unavailable`() {
        assertThat(
            AmbientMicVisibility.shouldShowTalkAction(
                hotwordEnabled = true,
                health = WakeWordHealth.Unavailable("model missing")
            )
        ).isTrue()
    }

    @Test
    fun `hide mic while detector is intentionally paused for STT`() {
        assertThat(
            AmbientMicVisibility.shouldShowTalkAction(
                hotwordEnabled = true,
                health = WakeWordHealth.Paused
            )
        ).isFalse()
    }
}
