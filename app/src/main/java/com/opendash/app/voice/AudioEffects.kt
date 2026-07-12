package com.opendash.app.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import timber.log.Timber

/**
 * Applies platform acoustic echo cancellation + noise suppression to a
 * capture session, when the device supports them. Without this, a
 * continuously-listening [android.media.AudioRecord] (wake word, STT)
 * picks up the device's own TTS/media output through the speaker and
 * produces false wakes or garbled transcripts whenever anything is
 * playing — the difference between "can be interrupted while talking"
 * and "can't hear you over itself" on a smart-speaker form factor.
 *
 * Both effects are best-effort: many budget devices report
 * `isAvailable() == false`, or `create()` can throw on hardware that
 * lies about support, so callers get `null` back and keep capturing
 * unaffected rather than crashing.
 */
object AudioEffects {

    fun applyAcousticEchoCanceler(audioSessionId: Int): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) return null
        return runCatching {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        }.onFailure {
            Timber.w(it, "Failed to create AcousticEchoCanceler")
        }.getOrNull()
    }

    fun applyNoiseSuppressor(audioSessionId: Int): NoiseSuppressor? {
        if (!NoiseSuppressor.isAvailable()) return null
        return runCatching {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        }.onFailure {
            Timber.w(it, "Failed to create NoiseSuppressor")
        }.getOrNull()
    }

    /** Releases any number of effects, tolerating individual failures so one bad release doesn't skip the rest. */
    fun release(vararg effects: AudioEffect?) {
        effects.forEach { effect ->
            runCatching { effect?.release() }
                .onFailure { Timber.w(it, "Failed to release audio effect") }
        }
    }
}
