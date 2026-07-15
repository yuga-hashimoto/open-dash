package com.opendash.app.voice.alert

/**
 * Applies [AlertCommand] decisions against the live timer/alarm backends.
 * Side-effecting but still unit-testable via injected lambdas.
 */
class AlertCommandExecutor(
    private val stopAlarm: (String) -> Boolean,
    private val cancelTimer: suspend (String) -> Boolean,
    private val snoozeAlarm: suspend (String, Int) -> Boolean,
    private val speak: suspend (String) -> Unit,
    private val stillRinging: suspend () -> Boolean = { false },
) {
    suspend fun execute(command: AlertCommand): AlertCommandOutcome {
        return when (command) {
            is AlertCommand.Stop -> executeStop(command.alert)
            is AlertCommand.Snooze -> executeSnooze(command.alert, command.minutes)
            is AlertCommand.SnoozeUnsupported -> {
                speak("Timers can't be snoozed. Say stop to cancel the timer.")
                AlertCommandOutcome.NeedsClarification(stillRinging = stillRinging())
            }
            is AlertCommand.Clarify -> {
                speak(clarificationMessage(command))
                AlertCommandOutcome.NeedsClarification(stillRinging = stillRinging())
            }
            AlertCommand.NoMatch ->
                AlertCommandOutcome.NoMatch(stillRinging = stillRinging())
            AlertCommand.NothingRinging ->
                AlertCommandOutcome.NoMatch(stillRinging = false)
        }
    }

    private suspend fun executeStop(alert: RingingAlert): AlertCommandOutcome {
        val ok = when (alert.kind) {
            AlertKind.TIMER -> cancelTimer(alert.id)
            AlertKind.ALARM -> stopAlarm(alert.id)
        }
        return if (ok) AlertCommandOutcome.Handled else AlertCommandOutcome.Failed
    }

    private suspend fun executeSnooze(alert: RingingAlert, minutes: Int): AlertCommandOutcome {
        if (alert.kind != AlertKind.ALARM) {
            speak("Timers can't be snoozed. Say stop to cancel the timer.")
            return AlertCommandOutcome.NeedsClarification(stillRinging = stillRinging())
        }
        // Silence first so the user hears the confirmation, then reschedule.
        stopAlarm(alert.id)
        val ok = snoozeAlarm(alert.id, minutes)
        return if (ok) AlertCommandOutcome.Handled else AlertCommandOutcome.Failed
    }

    private fun clarificationMessage(command: AlertCommand.Clarify): String {
        return when (command.intent) {
            AlertIntent.STOP ->
                "Multiple alerts are ringing. Say which one to stop, or cancel them by name."
            AlertIntent.SNOOZE ->
                "Multiple alarms are ringing. Say which alarm to snooze."
        }
    }
}
