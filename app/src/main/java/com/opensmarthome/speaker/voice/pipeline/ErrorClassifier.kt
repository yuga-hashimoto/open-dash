package com.opensmarthome.speaker.voice.pipeline

/**
 * Classifies an error message / exception cause into a user-facing category
 * with short, spoken-friendly copy. Avoids technical jargon.
 *
 * Inspired by Ava's isSTTError pattern — match common phrases in the
 * underlying error to pick the right recovery message.
 */
class ErrorClassifier {

    data class Recovery(
        val category: Category,
        val userSpokenMessage: String,
        val canRetry: Boolean
    )

    enum class Category {
        NO_PROVIDER,
        STT_FAILURE,
        LLM_TIMEOUT,
        NETWORK,
        PERMISSION,
        TOOL_FAILURE,
        UNKNOWN
    }

    fun classify(raw: String?, cause: Throwable? = null): Recovery {
        val lower = (raw?.lowercase().orEmpty() + " " +
            cause?.message?.lowercase().orEmpty())
            .replace('_', ' ') // ERROR_NO_MATCH → "error no match"

        return when {
            contains(lower, "no available", "no provider", "model not", "llm not") ->
                Recovery(
                    Category.NO_PROVIDER,
                    "I don't have an AI model ready yet. Open settings to download one.",
                    canRetry = false
                )
            contains(lower, "index out of range", "no match", "speech timeout", "list index") ->
                Recovery(
                    Category.STT_FAILURE,
                    "Sorry, I didn't catch that. Try again?",
                    canRetry = true
                )
            contains(lower, "timeout", "timed out", "deadline") ->
                Recovery(
                    Category.LLM_TIMEOUT,
                    "That took too long. Let me try again.",
                    canRetry = true
                )
            contains(lower, "unable to resolve", "connection", "unreachable", "host") ->
                Recovery(
                    Category.NETWORK,
                    "Network hiccup. Checking again.",
                    canRetry = true
                )
            contains(lower, "permission", "not granted", "denied") ->
                Recovery(
                    Category.PERMISSION,
                    "I need permission for that. Check settings.",
                    canRetry = false
                )
            contains(lower, "tool", "execution failed", "arguments") ->
                Recovery(
                    Category.TOOL_FAILURE,
                    "That didn't work. Want me to try a different way?",
                    canRetry = true
                )
            else -> Recovery(
                Category.UNKNOWN,
                "Something went wrong. Try again?",
                canRetry = true
            )
        }
    }

    private fun contains(haystack: String, vararg needles: String): Boolean =
        needles.any { it in haystack }
}
