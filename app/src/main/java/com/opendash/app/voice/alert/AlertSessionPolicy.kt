package com.opendash.app.voice.alert

/**
 * Bounded alert-listening session state machine.
 *
 * Starts a short STT listen only while something is ringing; after
 * stop/snooze/no-match budget/error it returns to normal wake-word
 * listening. Pure policy — no Android, no mic ownership.
 */
class AlertSessionPolicy(
    private val maxListenAttempts: Int = DEFAULT_MAX_LISTEN_ATTEMPTS,
) {
    var state: AlertSessionState = AlertSessionState.Idle
        private set

    private var listenAttempts = 0

    fun onRingingChanged(hasRinging: Boolean): List<AlertSessionAction> {
        return when {
            hasRinging && state == AlertSessionState.Idle -> {
                listenAttempts = 1
                state = AlertSessionState.Listening
                listOf(AlertSessionAction.StartListening)
            }
            !hasRinging && state == AlertSessionState.Listening -> {
                resetToIdle()
                listOf(AlertSessionAction.ResumeWakeWord)
            }
            else -> emptyList()
        }
    }

    fun onCommandResolved(outcome: AlertCommandOutcome): List<AlertSessionAction> {
        if (state != AlertSessionState.Listening) return emptyList()

        return when (outcome) {
            AlertCommandOutcome.Handled,
            AlertCommandOutcome.Failed -> {
                resetToIdle()
                listOf(AlertSessionAction.ResumeWakeWord)
            }
            is AlertCommandOutcome.NoMatch -> {
                if (outcome.stillRinging && listenAttempts < maxListenAttempts) {
                    listenAttempts += 1
                    listOf(AlertSessionAction.StartListening)
                } else {
                    resetToIdle()
                    listOf(AlertSessionAction.ResumeWakeWord)
                }
            }
            is AlertCommandOutcome.NeedsClarification -> {
                if (outcome.stillRinging && listenAttempts < maxListenAttempts) {
                    listenAttempts += 1
                    listOf(AlertSessionAction.StartListening)
                } else {
                    resetToIdle()
                    listOf(AlertSessionAction.ResumeWakeWord)
                }
            }
        }
    }

    private fun resetToIdle() {
        state = AlertSessionState.Idle
        listenAttempts = 0
    }

    companion object {
        const val DEFAULT_MAX_LISTEN_ATTEMPTS = 2
    }
}
