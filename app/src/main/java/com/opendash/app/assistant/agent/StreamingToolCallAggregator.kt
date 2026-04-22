package com.opendash.app.assistant.agent

import com.opendash.app.assistant.model.ToolCallRequest

/**
 * OpenAI-compatible SSE streams emit tool calls as *fragments*:
 *
 *   1. `{id:"call_1", name:"get_weather", arguments:""}`
 *   2. `{id:"",       name:"",            arguments:"{\"lo"}`
 *   3. `{id:"",       name:"",            arguments:"cation\":\"Tokyo\"}"}`
 *
 * Consumers that append each delta to a list verbatim end up with
 * three broken `ToolCallRequest`s — one with the real id but empty
 * arguments, two with empty ids and partial JSON. The downstream
 * dispatcher then runs the first one (empty args → tool falls back
 * to defaults or errors) and the later two as "Unknown tool".
 *
 * This accumulator glues the fragments back together. Heuristic:
 *   - A delta with a **non-empty id** that differs from the pending
 *     call's id starts a new call (and commits the pending one).
 *   - A delta with an **empty id** is a continuation: its `name`
 *     fragment is appended to the pending name, its `arguments`
 *     fragment is appended to the pending arguments.
 *   - A delta with an empty id and no content is a no-op.
 *   - A fragment that arrives before any call is pending becomes a
 *     standalone call rather than being silently dropped.
 *
 * This works for the common case of sequential tool streaming
 * (one tool's deltas arrive fully before the next). It does NOT
 * handle true multi-tool interleaved streaming — for that a
 * provider needs to expose the explicit `index` field and we'd
 * key the pending-map by index. OpenAI's Chat Completions API
 * streams sequentially in practice, so this is sufficient for
 * the current providers.
 */
class StreamingToolCallAggregator {

    private val completed = mutableListOf<ToolCallRequest>()
    private var pendingId: String = ""
    private var pendingName = StringBuilder()
    private var pendingArgs = StringBuilder()
    private var hasPending = false

    fun accept(delta: ToolCallRequest) {
        val isNewCall = delta.id.isNotEmpty() && delta.id != pendingId
        if (isNewCall) {
            commitPending()
            pendingId = delta.id
            pendingName.clear().append(delta.name)
            pendingArgs.clear().append(delta.arguments)
            hasPending = true
            return
        }
        // Continuation of the pending call (or the provider emitted an
        // args-only fragment before any id-bearing header).
        if (!hasPending) {
            if (delta.id.isEmpty() && delta.name.isEmpty() && delta.arguments.isEmpty()) {
                return
            }
            pendingId = delta.id
            pendingName.clear().append(delta.name)
            pendingArgs.clear().append(delta.arguments)
            hasPending = true
            return
        }
        // Sticky append.
        if (delta.name.isNotEmpty()) pendingName.append(delta.name)
        if (delta.arguments.isNotEmpty()) pendingArgs.append(delta.arguments)
    }

    fun complete(): List<ToolCallRequest> {
        commitPending()
        return completed.toList()
    }

    private fun commitPending() {
        if (!hasPending) return
        completed.add(
            ToolCallRequest(
                id = pendingId,
                name = pendingName.toString(),
                arguments = pendingArgs.toString()
            )
        )
        pendingId = ""
        pendingName.clear()
        pendingArgs.clear()
        hasPending = false
    }
}
