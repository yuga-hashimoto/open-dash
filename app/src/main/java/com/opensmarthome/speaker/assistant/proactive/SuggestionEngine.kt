package com.opensmarthome.speaker.assistant.proactive

import timber.log.Timber
import java.util.Calendar

class SuggestionEngine(
    private val rules: List<SuggestionRule>
) {

    suspend fun evaluate(): List<Suggestion> {
        val context = buildContext()
        return rules.mapNotNull { rule ->
            try {
                rule.evaluate(context)
            } catch (e: Exception) {
                Timber.w(e, "Rule ${rule.javaClass.simpleName} failed")
                null
            }
        }.sortedByDescending { it.priority.ordinal }
    }

    private fun buildContext(): ProactiveContext {
        val cal = Calendar.getInstance()
        return ProactiveContext(
            nowMs = System.currentTimeMillis(),
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        )
    }
}

/** Morning greeting: 6-9 AM. */
class MorningGreetingRule : SuggestionRule {
    override suspend fun evaluate(context: ProactiveContext): Suggestion? {
        return if (context.hourOfDay in 6..9) {
            Suggestion(
                id = "morning_greeting_${context.hourOfDay}",
                priority = Suggestion.Priority.LOW,
                message = "Good morning. Would you like a weather briefing?",
                suggestedAction = SuggestedAction("get_weather", emptyMap())
            )
        } else null
    }
}

/** Evening suggestion: 18-22 dim-the-lights prompt. */
class EveningLightsRule : SuggestionRule {
    override suspend fun evaluate(context: ProactiveContext): Suggestion? {
        return if (context.hourOfDay in 18..22) {
            Suggestion(
                id = "evening_lights_${context.hourOfDay}",
                priority = Suggestion.Priority.LOW,
                message = "It's getting late. Want me to dim the lights?"
            )
        } else null
    }
}

/** Night mode: suggest silent/do-not-disturb after 23:00. */
class NightQuietRule : SuggestionRule {
    override suspend fun evaluate(context: ProactiveContext): Suggestion? {
        return if (context.hourOfDay >= 23 || context.hourOfDay <= 4) {
            Suggestion(
                id = "night_quiet",
                priority = Suggestion.Priority.NORMAL,
                message = "It's late. Should I lower the volume and dim displays?",
                suggestedAction = SuggestedAction("set_volume", mapOf("level" to 20))
            )
        } else null
    }
}
