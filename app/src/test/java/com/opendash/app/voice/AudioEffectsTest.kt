package com.opendash.app.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AudioEffectsTest {

    @AfterEach
    fun tearDown() {
        unmockkStatic(AcousticEchoCanceler::class)
        unmockkStatic(NoiseSuppressor::class)
    }

    @Test
    fun `applyAcousticEchoCanceler returns null when device does not support it`() {
        mockkStatic(AcousticEchoCanceler::class)
        every { AcousticEchoCanceler.isAvailable() } returns false

        val result = AudioEffects.applyAcousticEchoCanceler(audioSessionId = 42)

        assertThat(result).isNull()
    }

    @Test
    fun `applyAcousticEchoCanceler creates and enables the effect when supported`() {
        mockkStatic(AcousticEchoCanceler::class)
        val effect = mockk<AcousticEchoCanceler>(relaxed = true)
        every { AcousticEchoCanceler.isAvailable() } returns true
        every { AcousticEchoCanceler.create(42) } returns effect

        val result = AudioEffects.applyAcousticEchoCanceler(audioSessionId = 42)

        assertThat(result).isSameInstanceAs(effect)
        io.mockk.verify { effect.enabled = true }
    }

    @Test
    fun `applyAcousticEchoCanceler swallows exceptions from create and returns null`() {
        mockkStatic(AcousticEchoCanceler::class)
        every { AcousticEchoCanceler.isAvailable() } returns true
        every { AcousticEchoCanceler.create(42) } throws UnsupportedOperationException("no hw")

        val result = AudioEffects.applyAcousticEchoCanceler(audioSessionId = 42)

        assertThat(result).isNull()
    }

    @Test
    fun `applyNoiseSuppressor returns null when device does not support it`() {
        mockkStatic(NoiseSuppressor::class)
        every { NoiseSuppressor.isAvailable() } returns false

        val result = AudioEffects.applyNoiseSuppressor(audioSessionId = 7)

        assertThat(result).isNull()
    }

    @Test
    fun `applyNoiseSuppressor creates and enables the effect when supported`() {
        mockkStatic(NoiseSuppressor::class)
        val effect = mockk<NoiseSuppressor>(relaxed = true)
        every { NoiseSuppressor.isAvailable() } returns true
        every { NoiseSuppressor.create(7) } returns effect

        val result = AudioEffects.applyNoiseSuppressor(audioSessionId = 7)

        assertThat(result).isSameInstanceAs(effect)
        io.mockk.verify { effect.enabled = true }
    }

    @Test
    fun `release swallows exceptions from individual effects and releases all`() {
        val ok = mockk<AcousticEchoCanceler>(relaxed = true)
        val throwing = mockk<NoiseSuppressor>(relaxed = true)
        every { throwing.release() } throws IllegalStateException("already released")

        AudioEffects.release(ok, throwing, null)

        io.mockk.verify { ok.release() }
        io.mockk.verify { throwing.release() }
    }
}
