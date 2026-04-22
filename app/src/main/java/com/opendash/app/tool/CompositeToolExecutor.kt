package com.opendash.app.tool

import com.opendash.app.tool.analytics.ToolUsageRecorder
import timber.log.Timber

/**
 * Combines multiple ToolExecutors into a single executor. Routes tool
 * calls to the appropriate executor based on tool name. Optionally
 * records usage statistics through any ToolUsageRecorder.
 *
 * Thread-safety: [toolToExecutor] is a `@Volatile` reference to an
 * immutable `Map`. [availableTools] builds a *new* map locally and
 * swaps the reference atomically at the end, so concurrent [execute]
 * calls (which happen during parallel tool dispatch introduced in
 * AgentToolDispatcher) only ever observe a fully-populated snapshot.
 * If any underlying executor's `availableTools()` throws, the previous
 * routing map stays intact rather than being wiped to empty.
 */
class CompositeToolExecutor(
    private val executors: List<ToolExecutor>,
    private val stats: ToolUsageRecorder? = null
) : ToolExecutor {

    @Volatile
    private var toolToExecutor: Map<String, ToolExecutor> = emptyMap()

    override suspend fun availableTools(): List<ToolSchema> {
        val allTools = mutableListOf<ToolSchema>()
        val nextMap = HashMap<String, ToolExecutor>()

        for (executor in executors) {
            val tools = executor.availableTools()
            for (tool in tools) {
                nextMap[tool.name] = executor
                allTools.add(tool)
            }
        }

        // Atomic swap — concurrent execute() readers either see the
        // entire old snapshot or the entire new one, never a mix.
        toolToExecutor = nextMap
        return allTools
    }

    override suspend fun execute(call: ToolCall): ToolResult {
        val snapshot = toolToExecutor
        val executor = snapshot[call.name]
        val result = if (executor == null) {
            // Tool unknown in current snapshot — refresh once in case a
            // late-registered executor has it.
            availableTools()
            val retryExecutor = toolToExecutor[call.name]
                ?: return recordAndReturn(
                    call.name,
                    ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
                )
            retryExecutor.execute(call)
        } else {
            Timber.d("Routing tool call '${call.name}' to ${executor.javaClass.simpleName}")
            executor.execute(call)
        }
        return recordAndReturn(call.name, result)
    }

    private fun recordAndReturn(toolName: String, result: ToolResult): ToolResult {
        stats?.record(toolName, success = result.success)
        return result
    }
}
