package com.opendash.app.tool.cache

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class CachingToolExecutorTest {

    private class CountingExecutor(
        private val handler: (ToolCall) -> ToolResult
    ) : ToolExecutor {
        val executeCount = AtomicInteger(0)
        override suspend fun availableTools(): List<ToolSchema> = emptyList()
        override suspend fun execute(call: ToolCall): ToolResult {
            executeCount.incrementAndGet()
            return handler(call)
        }
    }

    private fun call(name: String, args: Map<String, Any?> = emptyMap(), id: String = "id"): ToolCall =
        ToolCall(id, name, args)

    @Test
    fun `cacheable tool hits cache on second identical call`() = runTest {
        var count = 0
        val inner = CountingExecutor { ToolResult(it.id, true, "payload_${++count}") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        val first = cached.execute(call("get_weather", mapOf("location" to "Tokyo")))
        val second = cached.execute(call("get_weather", mapOf("location" to "Tokyo"), id = "id2"))

        assertThat(inner.executeCount.get()).isEqualTo(1)
        assertThat(first.data).isEqualTo("payload_1")
        assertThat(second.data).isEqualTo("payload_1")
        // callId on the cached result must reflect the *new* caller's id so
        // the ToolCallResult message matches the LLM's request slot.
        assertThat(second.callId).isEqualTo("id2")
    }

    @Test
    fun `different args bypass cache`() = runTest {
        var count = 0
        val inner = CountingExecutor { ToolResult(it.id, true, "payload_${++count}") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        cached.execute(call("get_weather", mapOf("location" to "Tokyo")))
        cached.execute(call("get_weather", mapOf("location" to "Osaka")))

        assertThat(inner.executeCount.get()).isEqualTo(2)
    }

    @Test
    fun `non-cacheable tool always delegates`() = runTest {
        val inner = CountingExecutor { ToolResult(it.id, true, "ok") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        cached.execute(call("set_timer", mapOf("seconds" to 60)))
        cached.execute(call("set_timer", mapOf("seconds" to 60)))

        assertThat(inner.executeCount.get()).isEqualTo(2)
    }

    @Test
    fun `failed results are not cached`() = runTest {
        var returnSuccess = false
        val inner = CountingExecutor {
            if (returnSuccess) ToolResult(it.id, true, "good")
            else ToolResult(it.id, false, "", "bad")
        }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        cached.execute(call("get_weather"))
        assertThat(inner.executeCount.get()).isEqualTo(1)

        returnSuccess = true
        val second = cached.execute(call("get_weather"))
        assertThat(second.success).isTrue()
        assertThat(inner.executeCount.get()).isEqualTo(2)
    }

    @Test
    fun `expired entries are refetched`() = runTest {
        var now = 0L
        val clock = { now }
        var count = 0
        val inner = CountingExecutor { ToolResult(it.id, true, "payload_${++count}") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds)),
            clock = clock
        )

        cached.execute(call("get_weather"))
        now += 61_000
        cached.execute(call("get_weather"))

        assertThat(inner.executeCount.get()).isEqualTo(2)
    }

    @Test
    fun `argument order does not affect cache key`() = runTest {
        val inner = CountingExecutor { ToolResult(it.id, true, "ok") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        cached.execute(call("get_weather", linkedMapOf<String, Any?>("a" to 1, "b" to 2)))
        cached.execute(call("get_weather", linkedMapOf<String, Any?>("b" to 2, "a" to 1)))

        assertThat(inner.executeCount.get()).isEqualTo(1)
    }

    @Test
    fun `invalidate clears cache for a tool`() = runTest {
        var count = 0
        val inner = CountingExecutor { ToolResult(it.id, true, "payload_${++count}") }
        val cached = CachingToolExecutor(
            delegate = inner,
            policy = CachePolicy(mapOf("get_weather" to 60.seconds))
        )

        cached.execute(call("get_weather"))
        cached.invalidate("get_weather")
        cached.execute(call("get_weather"))

        assertThat(inner.executeCount.get()).isEqualTo(2)
    }

    @Test
    fun `availableTools delegates to inner executor unchanged`() = runTest {
        val inner = object : ToolExecutor {
            override suspend fun availableTools() = listOf(ToolSchema("x", "", emptyMap()))
            override suspend fun execute(call: ToolCall) = ToolResult(call.id, true, "")
        }
        val cached = CachingToolExecutor(inner, CachePolicy(emptyMap()))
        assertThat(cached.availableTools().map { it.name }).containsExactly("x")
    }
}
