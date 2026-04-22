package com.opendash.app.tool.composite

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.util.UUID

/**
 * Helpers for composite tools (MorningBriefing / EveningBriefing /
 * Goodnight / Presence) that used to fire sub-tool calls sequentially.
 * Running weather → news → calendar one after the other stacks
 * ~300-1000 ms of network latency per call on top of each other; for a
 * voice-first "good morning" flow that's the difference between feeling
 * instant and feeling sluggish.
 *
 * Each helper runs the non-null sub-calls in parallel via
 * `coroutineScope + async/awaitAll`, captures per-call failure into a
 * sentinel string (so one sub-tool failing doesn't cancel siblings), and
 * preserves the caller-provided ordering when stitching the final JSON
 * object together.
 */
internal object ParallelSubTools {

    data class Sub(
        val enabled: Boolean,
        val label: String,
        val toolName: String,
        val arguments: Map<String, Any?> = emptyMap(),
        /**
         * [Value] — the sub-tool's raw JSON/text becomes the RHS verbatim.
         * [Success] — the sub-tool's success flag becomes a `true`/`false`
         * literal. Used by action-shaped composites (Goodnight, Presence).
         */
        val render: RenderKind = RenderKind.Value
    )

    enum class RenderKind { Value, Success }

    /**
     * Runs the [subs] where `enabled == true` in parallel against [inner]
     * and returns a JSON object string preserving the original ordering.
     * Per-call failures become either `"label":null` (Value) or
     * `"label":false` (Success).
     */
    suspend fun runParallel(
        inner: ToolExecutor,
        idPrefix: String,
        subs: List<Sub>,
        logTag: String
    ): String {
        val enabled = subs.filter { it.enabled }
        if (enabled.isEmpty()) return "{}"

        val results: List<String> = coroutineScope {
            enabled
                .map { sub ->
                    async {
                        runOne(inner, sub, idPrefix, logTag)
                    }
                }
                .awaitAll()
        }
        return "{${results.joinToString(",")}}"
    }

    private suspend fun runOne(
        inner: ToolExecutor,
        sub: Sub,
        idPrefix: String,
        logTag: String
    ): String {
        val callId = "${idPrefix}_${UUID.randomUUID()}"
        val call = ToolCall(id = callId, name = sub.toolName, arguments = sub.arguments)
        val result: ToolResult? = try {
            inner.execute(call)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "%s inner call failed: %s", logTag, sub.toolName)
            null
        }
        return when (sub.render) {
            RenderKind.Value -> {
                val rhs = if (result?.success == true) result.data else "null"
                "\"${sub.label}\":$rhs"
            }
            RenderKind.Success -> {
                val rhs = if (result?.success == true) "true" else "false"
                "\"${sub.label}\":$rhs"
            }
        }
    }
}
