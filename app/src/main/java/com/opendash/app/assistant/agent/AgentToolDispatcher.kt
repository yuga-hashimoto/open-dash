package com.opendash.app.assistant.agent

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * Runs a batch of [ToolCallRequest] from one LLM turn in parallel and
 * returns the resulting [AssistantMessage.ToolCallResult] list preserving
 * the original ordering that the LLM emitted. This is the OpenClaw-style
 * agent loop (P16.7): when the model asks for `get_weather` AND `get_time`
 * in the same response, running them sequentially serialises ~200 ms of
 * tool latency per additional call. Parallel dispatch halves that for two
 * tools and scales with the request count.
 *
 * Why preserved ordering matters: the `tool_call_id`-keyed LLM protocols
 * still match by id, but chat-template renderers and our own conversation
 * persistence assume the result list lines up with the call list, so the
 * transcript stays readable.
 *
 * Failure isolation: one tool's exception or error result must not cancel
 * the others. We collect each branch's outcome via [runCatching] so a
 * single kaboom only marks that slot as an error.
 */
class AgentToolDispatcher(
    private val toolExecutor: ToolExecutor,
    private val moshi: Moshi? = null
) {

    /**
     * Optional per-tool timing / logging hooks. The dispatcher invokes
     * [onStart] just before dispatching the underlying executor and
     * [onEnd] with the same tool name + success flag after the executor
     * returns (or throws). Exceptions from the callbacks are swallowed to
     * keep the tool pipeline resilient against buggy tracers.
     */
    fun interface ToolTracer {
        suspend fun trace(phase: Phase, request: ToolCallRequest, success: Boolean?)
        enum class Phase { START, END }
    }

    suspend fun dispatch(
        requests: List<ToolCallRequest>,
        tracer: ToolTracer? = null
    ): List<AssistantMessage.ToolCallResult> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) {
            return listOf(runOne(requests.first(), tracer))
        }
        return coroutineScope {
            requests
                .map { req -> async { runOne(req, tracer) } }
                .awaitAll()
        }
    }

    private suspend fun runOne(
        request: ToolCallRequest,
        tracer: ToolTracer?
    ): AssistantMessage.ToolCallResult {
        val args = parseArguments(request.arguments)
        val call = ToolCall(id = request.id, name = request.name, arguments = args)
        tracer?.let {
            try { it.trace(ToolTracer.Phase.START, request, null) } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
        val outcome = try {
            Result.success(toolExecutor.execute(call))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val resultSuccess = outcome.getOrNull()?.success == true
        tracer?.let {
            try { it.trace(ToolTracer.Phase.END, request, resultSuccess) } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
        return outcome
            .fold(
                onSuccess = { result ->
                    AssistantMessage.ToolCallResult(
                        callId = request.id,
                        result = if (result.success) result.data else (result.error ?: "Error"),
                        isError = !result.success
                    )
                },
                onFailure = { t ->
                    Timber.e(t, "Tool '${request.name}' threw")
                    AssistantMessage.ToolCallResult(
                        callId = request.id,
                        result = t.message ?: t.javaClass.simpleName,
                        isError = true
                    )
                }
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArguments(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        val adapter = moshi?.adapter(Map::class.java) ?: defaultAdapter
        val raw = try {
            adapter.fromJson(json) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse tool arguments: $json")
            return emptyMap()
        }
        // Smaller on-device LLMs sometimes emit {"seconds": "60"} or
        // {"include_news": "true"} — the right value, wrong JSON type.
        // Massage the parsed map so downstream `as? Number` / `as?
        // Boolean` checks still hit. Conservative: only round-trip-safe
        // numeric strings and exact true/false words get coerced.
        return ToolArgumentCoercion.coerceMap(raw)
    }

    private companion object {
        private val defaultAdapter by lazy {
            Moshi.Builder().build().adapter(Map::class.java)
        }
    }
}
