package com.opendash.app.voice.alert

enum class AlertIntent {
    STOP,
    SNOOZE,
}

sealed class AlertCommand {
    data class Stop(val alert: RingingAlert) : AlertCommand()
    data class Snooze(val alert: RingingAlert, val minutes: Int = DEFAULT_SNOOZE_MINUTES) : AlertCommand()
    data class SnoozeUnsupported(val alert: RingingAlert) : AlertCommand()
    data class Clarify(val alerts: List<RingingAlert>, val intent: AlertIntent) : AlertCommand()
    data object NoMatch : AlertCommand()
    data object NothingRinging : AlertCommand()

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 9
    }
}

sealed class AlertCommandOutcome {
    data object Handled : AlertCommandOutcome()
    data class NoMatch(val stillRinging: Boolean) : AlertCommandOutcome()
    data class NeedsClarification(val stillRinging: Boolean) : AlertCommandOutcome()
    data object Failed : AlertCommandOutcome()
}

enum class AlertSessionState {
    Idle,
    Listening,
}

sealed class AlertSessionAction {
    data object StartListening : AlertSessionAction()
    data object ResumeWakeWord : AlertSessionAction()
}
