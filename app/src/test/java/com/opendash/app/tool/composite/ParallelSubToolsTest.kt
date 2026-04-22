package com.opendash.app.tool.composite

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ParallelSubToolsTest {

    private class RecordingExecutor(
        private val onExecute: suspend (ToolCall) -> ToolResult
    ) : ToolExecutor {
        val inFlight = AtomicInteger(0)
        var peakInFlight: Int = 0
            private set
        val invoked = mutableListOf<String>()

        override suspend fun availableTools(): List<ToolSchema> = emptyList()

        override suspend fun execute(call: ToolCall): ToolResult {
            synchronized(invoked) { invoked.add(call.name) }
            val now = inFlight.incrementAndGet()
            if (now > peakInFlight) peakInFlight = now
            try {
                return onExecute(call)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `all enabled subs execute in parallel`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val exec = RecordingExecutor { call ->
            gate.await()
            ToolResult(call.id, true, "\"${call.name}\"")
        }

        val subs = listOf(
            ParallelSubTools.Sub(true, "w", "get_weather"),
            ParallelSubTools.Sub(true, "n", "get_news"),
            ParallelSubTools.Sub(true, "c", "get_calendar_events")
        )

        val deferred = async {
            ParallelSubTools.runParallel(exec, idPrefix = "x", subs = subs, logTag = "test")
        }

        while (exec.inFlight.get() < 3) {
            delay(1)
        }
        gate.complete(Unit)
        val json = deferred.await()

        assertThat(exec.peakInFlight).isEqualTo(3)
        assertThat(json).isEqualTo("""{"w":"get_weather","n":"get_news","c":"get_calendar_events"}""")
    }

    @Test
    fun `ordering preserved regardless of completion order`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val exec = RecordingExecutor { call ->
            if (call.name == "slow_one") {
                slow.await()
            } else {
                slow.complete(Unit)
            }
            ToolResult(call.id, true, "\"${call.name}\"")
        }

        val subs = listOf(
            ParallelSubTools.Sub(true, "a", "slow_one"),
            ParallelSubTools.Sub(true, "b", "fast_one")
        )

        val json = ParallelSubTools.runParallel(exec, "ord", subs, "test")

        assertThat(json).isEqualTo("""{"a":"slow_one","b":"fast_one"}""")
    }

    @Test
    fun `disabled subs are skipped`() = runTest {
        val exec = RecordingExecutor { call ->
            ToolResult(call.id, true, "\"ran\"")
        }
        val subs = listOf(
            ParallelSubTools.Sub(true, "a", "get_a"),
            ParallelSubTools.Sub(false, "b", "get_b"),
            ParallelSubTools.Sub(true, "c", "get_c")
        )

        val json = ParallelSubTools.runParallel(exec, "skip", subs, "test")

        assertThat(exec.invoked).containsExactly("get_a", "get_c")
        assertThat(json).isEqualTo("""{"a":"ran","c":"ran"}""")
    }

    @Test
    fun `sub tool throws renders null entry without cancelling siblings`() = runTest {
        val exec = RecordingExecutor { call ->
            if (call.name == "bad") throw IllegalStateException("boom")
            ToolResult(call.id, true, "\"${call.name}\"")
        }
        val subs = listOf(
            ParallelSubTools.Sub(true, "a", "ok1"),
            ParallelSubTools.Sub(true, "b", "bad"),
            ParallelSubTools.Sub(true, "c", "ok3")
        )

        val json = ParallelSubTools.runParallel(exec, "err", subs, "test")

        assertThat(json).isEqualTo("""{"a":"ok1","b":null,"c":"ok3"}""")
    }

    @Test
    fun `Success render kind emits true or false literal`() = runTest {
        val exec = RecordingExecutor { call ->
            if (call.name == "bad") ToolResult(call.id, false, "", "fail")
            else ToolResult(call.id, true, "whatever")
        }
        val subs = listOf(
            ParallelSubTools.Sub(true, "ok", "ok1", render = ParallelSubTools.RenderKind.Success),
            ParallelSubTools.Sub(true, "ng", "bad", render = ParallelSubTools.RenderKind.Success)
        )

        val json = ParallelSubTools.runParallel(exec, "p", subs, "test")

        assertThat(json).isEqualTo("""{"ok":true,"ng":false}""")
    }

    @Test
    fun `empty subs returns empty JSON object`() = runTest {
        val exec = RecordingExecutor { ToolResult("x", true, "whatever") }
        val json = ParallelSubTools.runParallel(exec, "e", emptyList(), "test")
        assertThat(json).isEqualTo("{}")
    }
}
