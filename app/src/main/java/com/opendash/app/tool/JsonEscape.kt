package com.opendash.app.tool

/**
 * Escapes a string for embedding inside a hand-built JSON string
 * literal. [ToolExecutor] implementations build [ToolResult.data]
 * payloads via raw string templates rather than a JSON library (small,
 * fixed shapes; no need for full serialization) — this is the shared
 * escape helper for that, replacing the same private extension
 * function that used to be duplicated per-file.
 */
fun String.escapeJson(): String = buildString(length) {
    for (c in this@escapeJson) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
