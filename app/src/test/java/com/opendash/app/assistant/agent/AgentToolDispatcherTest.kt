package com.opendash.app.assistant.agent

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * AgentToolDispatcher should run tool calls in parallel when the LLM issues
 * multiple in one round, while preserving the original ordering of
 * [AssistantMessage.ToolCallResult] (LLMs expect results aligned to the
 * call list). Errors in one tool must not cancel the others.
 */
class AgentToolDispatcherTest {

    private class RecordingExecutor(
        private val scripts: Map<String, suspend () -> ToolResult>
    ) : ToolExecutor {
        val calls = mutableListOf<String>()
        val inFlight = AtomicInteger(0)
        var peakInFlight: Int = 0
            private set

        override suspend fun availableTools(): List<ToolSchema> = emptyList()

        override suspend fun execute(call: ToolCall): ToolResult {
            synchronized(calls) { calls.add(call.name) }
            val now = inFlight.incrementAndGet()
            if (now > peakInFlight) peakInFlight = now
            return try {
                scripts.getValue(call.name).invoke()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun req(id: String, name: String) = ToolCallRequest(id, name, "{}")

    @Test
    fun `dispatch executes multiple tool calls in parallel`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val scripts = mapOf(
            "a" to suspend { gate.await(); ToolResult("a", true, "A") },
            "b" to suspend { gate.await(); ToolResult("b", true, "B") },
            "c" to suspend { gate.await(); ToolResult("c", true, "C") }
        )
        val exec = RecordingExecutor(scripts)
        val dispatcher = AgentToolDispatcher(exec)

        val job = async {
            dispatcher.dispatch(listOf(req("1", "a"), req("2", "b"), req("3", "c")))
        }

        // All three must enter `execute` before any can finish.
        while (exec.inFlight.get() < 3) {
            delay(1)
        }
        gate.complete(Unit)
        val results = job.await()

        assertThat(exec.peakInFlight).isEqualTo(3)
        assertThat(results).hasSize(3)
    }

    @Test
    fun `results preserve original ordering even if later calls finish first`() = runTest {
        val firstDone = CompletableDeferred<Unit>()
        val scripts = mapOf(
            "slow" to suspend {
                firstDone.await()
                ToolResult("1", true, "slow_data")
            },
            "fast" to suspend {
                firstDone.complete(Unit)
                ToolResult("2", true, "fast_data")
            }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))

        val results = dispatcher.dispatch(listOf(req("1", "slow"), req("2", "fast")))

        assertThat(results).hasSize(2)
        assertThat(results[0].callId).isEqualTo("1")
        assertThat(results[0].result).isEqualTo("slow_data")
        assertThat(results[1].callId).isEqualTo("2")
        assertThat(results[1].result).isEqualTo("fast_data")
    }

    @Test
    fun `failures are isolated per call`() = runTest {
        val scripts = mapOf<String, suspend () -> ToolResult>(
            "ok" to { ToolResult("1", true, "OK") },
            "boom" to { throw IllegalStateException("kaboom") },
            "also_ok" to { ToolResult("3", true, "OK3") }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))

        val results = dispatcher.dispatch(
            listOf(req("1", "ok"), req("2", "boom"), req("3", "also_ok"))
        )

        assertThat(results).hasSize(3)
        assertThat(results[0].isError).isFalse()
        assertThat(results[1].isError).isTrue()
        assertThat(results[1].result).contains("kaboom")
        assertThat(results[2].isError).isFalse()
    }

    @Test
    fun `single call still works`() = runTest {
        val scripts = mapOf<String, suspend () -> ToolResult>(
            "only" to { ToolResult("x", true, "only") }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))

        val results = dispatcher.dispatch(listOf(req("x", "only")))

        assertThat(results).hasSize(1)
        assertThat(results[0].callId).isEqualTo("x")
        assertThat(results[0].result).isEqualTo("only")
    }

    @Test
    fun `empty call list returns empty results`() = runTest {
        val dispatcher = AgentToolDispatcher(RecordingExecutor(emptyMap()))
        val results = dispatcher.dispatch(emptyList())
        assertThat(results).isEmpty()
    }

    @Test
    fun `cancellation cancels all in-flight tool calls`() = runTest {
        val scripts = mapOf<String, suspend () -> ToolResult>(
            "long_a" to { delay(10_000); ToolResult("1", true, "a") },
            "long_b" to { delay(10_000); ToolResult("2", true, "b") }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))

        val outer = async {
            dispatcher.dispatch(listOf(req("1", "long_a"), req("2", "long_b")))
        }

        outer.cancel()

        try {
            outer.await()
            error("should have been cancelled")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `tracer receives START and END for every call`() = runTest {
        val scripts = mapOf<String, suspend () -> ToolResult>(
            "a" to { ToolResult("1", true, "A") },
            "b" to { ToolResult("2", true, "B") }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))
        val events = mutableListOf<String>()
        val tracer = AgentToolDispatcher.ToolTracer { phase, req, success ->
            synchronized(events) {
                events.add("${phase}:${req.name}:$success")
            }
        }

        dispatcher.dispatch(listOf(req("1", "a"), req("2", "b")), tracer)

        assertThat(events.filter { it.startsWith("START") }).hasSize(2)
        assertThat(events.filter { it.startsWith("END") }).hasSize(2)
        assertThat(events).contains("END:a:true")
        assertThat(events).contains("END:b:true")
    }

    @Test
    fun `tool result error propagates to result message`() = runTest {
        val scripts = mapOf<String, suspend () -> ToolResult>(
            "err" to { ToolResult("x", false, "", "bad args") }
        )
        val dispatcher = AgentToolDispatcher(RecordingExecutor(scripts))

        val results = dispatcher.dispatch(listOf(req("x", "err")))

        assertThat(results[0].isError).isTrue()
        assertThat(results[0].result).isEqualTo("bad args")
    }
}
