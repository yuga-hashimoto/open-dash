package com.opendash.app.tool.cache

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Decorator that memoises successful read-only tool results for a short
 * TTL configured per tool name.
 *
 * Why: "What's the weather?" → LLM calls `get_weather` → RTT to
 * Open-Meteo. Two minutes later the user asks "will I need a coat?" → LLM
 * again calls `get_weather` with the same location. Same network round
 * trip, same answer, same latency. A 5-minute cache collapses the second
 * call to an in-memory read and roughly halves perceived latency for
 * follow-up queries.
 *
 * Scoped to *read-only* tools via [CachePolicy]. Writes (timers, device
 * control, messages) must not be cached — they're intentionally absent
 * from the allowlist.
 *
 * Failed results are never cached; a transient network blip shouldn't
 * poison the next 5 minutes.
 *
 * Cache keys normalise argument ordering by sorting entries, so
 * `{location:Tokyo, units:c}` and `{units:c, location:Tokyo}` share a
 * slot.
 */
class CachingToolExecutor(
    private val delegate: ToolExecutor,
    private val policy: CachePolicy,
    private val clock: () -> Long = System::currentTimeMillis
) : ToolExecutor {

    private data class Entry(val result: ToolResult, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override suspend fun availableTools(): List<ToolSchema> = delegate.availableTools()

    override suspend fun execute(call: ToolCall): ToolResult {
        val ttl = policy.ttls[call.name]
            ?: return delegate.execute(call)

        val key = cacheKey(call.name, call.arguments)
        val now = clock()
        val cached = cache[key]
        if (cached != null && cached.expiresAt > now) {
            return cached.result.copy(callId = call.id)
        }

        val fresh = delegate.execute(call)
        if (fresh.success) {
            cache[key] = Entry(fresh, now + ttl.inWholeMilliseconds)
        }
        return fresh
    }

    /**
     * Drop all cached entries for [toolName]. Callers that know state
     * changed out-of-band (e.g. the user toggled a device) can flush to
     * force a fresh fetch on next call.
     */
    fun invalidate(toolName: String) {
        val prefix = "$toolName|"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    /** Clears the entire cache. */
    fun clear() {
        cache.clear()
    }

    private fun cacheKey(name: String, args: Map<String, Any?>): String {
        if (args.isEmpty()) return "$name|"
        val sorted = args.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        return "$name|$sorted"
    }
}

/**
 * Per-tool TTL allowlist. Absence from the map = always delegate.
 *
 * Defaults are tuned for smart-speaker follow-ups: short enough that
 * weather / news / search stays fresh-feeling, long enough to catch the
 * back-to-back "what's the weather" → "will it rain?" pattern.
 */
data class CachePolicy(val ttls: Map<String, Duration>)
