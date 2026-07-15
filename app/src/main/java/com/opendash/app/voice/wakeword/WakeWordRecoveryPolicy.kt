package com.opendash.app.voice.wakeword

/**
 * Pure policy for bounded self-recovery after a wake-word detector failure.
 * VoiceService owns scheduling; this class decides whether/how long to wait.
 */
class WakeWordRecoveryPolicy {

    fun shouldRecover(health: WakeWordHealth, attempt: Int): Boolean {
        if (health !is WakeWordHealth.Failed) return false
        return attempt < MAX_ATTEMPTS
    }

    fun backoffMs(attempt: Int): Long {
        val raw = BASE_BACKOFF_MS * (attempt + 1)
        return raw.coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
