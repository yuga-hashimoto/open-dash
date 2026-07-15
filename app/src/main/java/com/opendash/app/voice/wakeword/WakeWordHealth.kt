package com.opendash.app.voice.wakeword

/**
 * Coarse health of a [WakeWordDetector] session.
 *
 * - [Listening] — actively capturing audio for the keyword.
 * - [Paused] — intentionally stopped for an STT turn (not a failure).
 * - [Unavailable] — never started (disabled, missing model, library absent).
 * - [Failed] — started but exhausted internal retries / hard failure.
 */
sealed class WakeWordHealth {
    data object Listening : WakeWordHealth()
    data object Paused : WakeWordHealth()
    data class Unavailable(val reason: String) : WakeWordHealth()
    data class Failed(val reason: String) : WakeWordHealth()
}
