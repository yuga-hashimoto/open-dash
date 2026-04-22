package com.opendash.app.assistant.agent

/**
 * LLMs — especially smaller on-device ones — sometimes emit tool
 * arguments with the right value but the wrong JSON type. Common
 * failures:
 *   {"seconds": "60"}       instead of 60
 *   {"include_news": "true"} instead of true
 *   {"level": "22.5"}        instead of 22.5
 *
 * Tools use `as? Number` / `as? Boolean` patterns so these calls
 * silently fall back to defaults, and the user sees no reaction.
 *
 * This helper massages the parsed Map *once* at the dispatcher
 * boundary so every downstream tool sees the expected types without
 * needing to duplicate coercion logic. Rules are conservative: only
 * coerce when the string is unambiguously a number (round-trip
 * preserving) or the exact case-insensitive word "true" / "false".
 * Anything else — phone numbers with leading zeros, whitespace-padded
 * values, free-form text — stays as-is.
 */
object ToolArgumentCoercion {

    fun coerceMap(args: Map<String, Any?>): Map<String, Any?> {
        if (args.isEmpty()) return args
        val out = LinkedHashMap<String, Any?>(args.size)
        for ((key, value) in args) {
            out[key] = coerceValue(value)
        }
        return out
    }

    private fun coerceValue(value: Any?): Any? {
        if (value !is String) return value
        asBoolean(value)?.let { return it }
        asNumber(value)?.let { return it }
        return value
    }

    private fun asBoolean(s: String): Boolean? = when (s.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun asNumber(s: String): Double? {
        if (s.isEmpty()) return null
        // No whitespace padding — strings wrapped in spaces are almost
        // certainly free-form text.
        if (s.trim() != s) return null
        val parsed = s.toDoubleOrNull() ?: return null
        // Round-trip guard: phone numbers / zip codes / ids with leading
        // zeros parse to a number but drop information (e.g. "0123" →
        // 123.0 → "123.0"). Preserve the original string in that case.
        if (!looksLikeCanonicalNumber(s, parsed)) return null
        return parsed
    }

    private fun looksLikeCanonicalNumber(original: String, parsed: Double): Boolean {
        // Allow integer shapes ("60", "-3") as well as decimal shapes
        // ("3.14", "-0.5"). Reject leading-zero integers ("0123"),
        // scientific notation shortcuts ("1e5") we'd prefer the caller
        // type explicitly, and anything the caller wrote with extras.
        if (original.any { it == 'e' || it == 'E' }) return false
        val hasLeadingZero = original.length > 1 &&
            original[0] == '0' &&
            original[1] != '.' ||
            (original.length > 2 &&
                original[0] == '-' &&
                original[1] == '0' &&
                original[2] != '.')
        if (hasLeadingZero) return false
        // Integer vs decimal round-trip: if parsed is integral, compare
        // against the long form; otherwise compare as Double.toString().
        return if (parsed % 1.0 == 0.0 && !original.contains('.')) {
            parsed.toLong().toString() == original ||
                ("-" + parsed.toLong().toString()) == original
        } else {
            // Accept a decimal representation that matches the input
            // shape, allowing for trailing zeros ("22.50" → 22.5) only
            // when the leading digits line up.
            parsed.toString() == original || "${parsed.toLong()}.${original.substringAfter('.')}" == original ||
                original.toDoubleOrNull() == parsed && original.matches(Regex("-?\\d+\\.\\d+"))
        }
    }
}
