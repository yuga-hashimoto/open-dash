package com.opendash.app.assistant.skills.runtime

/**
 * Builds the JS source actually handed to the engine for a [SkillScript] execution.
 *
 * QuickJs's low-level `evaluate()` only marshals primitive completion values back
 * to the JVM (string/number/boolean/null/array) -- a bare JS object throws
 * `Cannot marshal value ... to Java` (see `Context::toJavaObject` in
 * cashapp/zipline's native sources). [SkillScriptRuntime]'s contract is
 * string-in/string-out, so the wrapper always normalizes the script's return
 * value to a string itself (via `JSON.stringify` for objects/arrays) rather
 * than leaving that marshaling failure mode reachable from a script author's
 * `return {...}`.
 *
 * `input` is embedded as a JS string literal rather than passed as a real
 * function argument, since `QuickJs.evaluate` only accepts a source string --
 * there is no separate argument channel at this API level.
 *
 * `memory` (pre-fetched, read-only key/value pairs — see [SkillScriptContext.memory])
 * is embedded the same way: injected as a JS object literal built from
 * escaped string-literal entries, with a `read_memory(key)` global defined
 * over it. Since the whole memory map is baked into the source text before
 * a single synchronous `evaluate()` call, this is a real, working bridge for
 * *reads* despite `QuickJs` having no live host-callback mechanism -- there's
 * nothing to call back into, the data already exists in the script's scope.
 */
internal object SkillScriptWrapper {

    fun wrap(source: String, input: String, memory: Map<String, String> = emptyMap()): String = buildString {
        append("(function() {\n")
        append("  var input = ")
        append(input.toJsStringLiteral())
        append(";\n")
        append("  var __memory = ")
        append(memory.toJsObjectLiteral())
        append(";\n")
        append("  function read_memory(key) {\n")
        append("    return Object.prototype.hasOwnProperty.call(__memory, key) ? __memory[key] : null;\n")
        append("  }\n")
        append("  var __skillResult = (function() {\n")
        append(source)
        append("\n  })();\n")
        append(
            "  if (__skillResult === undefined || __skillResult === null) return \"\";\n"
        )
        append("  if (typeof __skillResult === \"object\") return JSON.stringify(__skillResult);\n")
        append("  return String(__skillResult);\n")
        append("})()")
    }

    /**
     * Escapes for embedding inside a double-quoted JS string literal. Also escapes
     * U+2028/U+2029 (line/paragraph separator) -- valid in a JSON string but historically
     * a syntax error unescaped inside a JS string literal in engines predating ES2019.
     */
    private fun String.toJsStringLiteral(): String = buildString {
        append('"')
        for (c in this@toJsStringLiteral) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
        }
        append('"')
    }

    /** Builds `{"key": "value", ...}` with every key and value string-literal escaped. */
    private fun Map<String, String>.toJsObjectLiteral(): String = buildString {
        append('{')
        entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(key.toJsStringLiteral())
            append(':')
            append(value.toJsStringLiteral())
        }
        append('}')
    }
}
