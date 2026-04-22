package com.opendash.app.assistant.proactive

import com.opendash.app.util.ThermalLevel

/**
 * Proactive nudge when the tablet is running hot enough that the OS
 * has flagged thermal throttling. Pairs with the existing battery-saver
 * gating in VoiceService (which silently pauses wake-word on WARM/HOT):
 * this rule tells the user *why* background work just paused so they
 * can move the device somewhere cooler instead of assuming it's broken.
 *
 * Dedupe: id stable within a bucket so SuggestionState does not re-surface
 * the card on every poll while the temperature lingers.
 *
 * Levels come from [com.opendash.app.util.ThermalMonitor]. The rule is
 * supplier-based so tests can feed synthetic samples without constructing
 * the real monitor (which registers an Android PowerManager listener
 * unavailable on pure JVM).
 */
class ThermalWarningRule(
    private val levelSupplier: () -> ThermalLevel,
) : SuggestionRule {

    override suspend fun evaluate(context: ProactiveContext): Suggestion? {
        return when (levelSupplier()) {
            ThermalLevel.NORMAL -> null
            ThermalLevel.WARM -> Suggestion(
                id = "thermal_warm",
                priority = Suggestion.Priority.NORMAL,
                message = "The tablet is warming up. I'll ease off background work " +
                    "until it cools. Moving it out of direct sunlight can help.",
                suggestedAction = null,
                expiresAtMs = context.nowMs + EXPIRY_WINDOW_MS,
            )
            ThermalLevel.HOT -> Suggestion(
                id = "thermal_hot",
                priority = Suggestion.Priority.HIGH,
                message = "The tablet is overheating — wake-word listening is paused. " +
                    "Please give it a break somewhere cooler before it throttles further.",
                suggestedAction = null,
                expiresAtMs = context.nowMs + EXPIRY_WINDOW_MS,
            )
        }
    }

    private companion object {
        // Five minutes — short enough that the card disappears promptly
        // once thermal returns to NORMAL; long enough that we don't spam
        // the user with repeat cards while they cool the device down.
        const val EXPIRY_WINDOW_MS = 5L * 60 * 1_000
    }
}
