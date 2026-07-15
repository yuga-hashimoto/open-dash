package com.opendash.app.voice.alert

/**
 * Routes unqualified stop/cancel/snooze phrases to the currently ringing
 * alert during an alert-listening session. Does **not** own explicit
 * id/label cancel paths — those stay with FastPath matchers
 * ([com.opendash.app.voice.fastpath.CancelTimerByLabelMatcher],
 * [com.opendash.app.voice.fastpath.CancelAllTimersMatcher], etc.).
 *
 * Pure policy: no Android, no LLM, no side effects.
 */
class AlertCommandRouter {

    fun route(utterance: String, ringing: List<RingingAlert>): AlertCommand {
        val normalized = normalize(utterance)
        if (normalized.isEmpty()) return AlertCommand.NoMatch

        // Explicit multi-word cancel phrases remain FastPath territory.
        if (isExplicitScopedCancel(normalized)) return AlertCommand.NoMatch

        val intent = detectIntent(normalized) ?: return AlertCommand.NoMatch
        if (ringing.isEmpty()) return AlertCommand.NothingRinging

        return when (intent) {
            AlertIntent.STOP -> when (ringing.size) {
                1 -> AlertCommand.Stop(ringing.single())
                else -> AlertCommand.Clarify(ringing, AlertIntent.STOP)
            }
            AlertIntent.SNOOZE -> {
                val alarms = ringing.filter { it.kind == AlertKind.ALARM }
                val timers = ringing.filter { it.kind == AlertKind.TIMER }
                when {
                    alarms.size == 1 && timers.isEmpty() ->
                        AlertCommand.Snooze(alarms.single())
                    alarms.size > 1 ->
                        AlertCommand.Clarify(alarms, AlertIntent.SNOOZE)
                    alarms.isEmpty() && timers.size == 1 ->
                        AlertCommand.SnoozeUnsupported(timers.single())
                    else ->
                        AlertCommand.Clarify(ringing, AlertIntent.SNOOZE)
                }
            }
        }
    }

    private fun normalize(utterance: String): String =
        utterance
            .trim()
            .lowercase()
            .replace(Regex("""[.!?。！？]+$"""), "")
            .replace(Regex("""\s+"""), " ")

    private fun detectIntent(normalized: String): AlertIntent? = when {
        STOP_PHRASES.contains(normalized) -> AlertIntent.STOP
        SNOOZE_PHRASES.contains(normalized) -> AlertIntent.SNOOZE
        else -> null
    }

    private fun isExplicitScopedCancel(normalized: String): Boolean {
        if (normalized.contains("timer") || normalized.contains("タイマー")) return true
        if (normalized.contains("alarm") || normalized.contains("アラーム")) return true
        if (normalized.contains("all ") || normalized.contains("全部") || normalized.contains("全て")) {
            return true
        }
        return false
    }

    companion object {
        private val STOP_PHRASES = setOf(
            "stop",
            "cancel",
            "dismiss",
            "quiet",
            "silence",
            "止めて",
            "やめて",
            "キャンセル",
            "ストップ",
            "消して",
            "黙って",
        )

        private val SNOOZE_PHRASES = setOf(
            "snooze",
            "スヌーズ",
            "もう少し",
            "あとで",
        )
    }
}
