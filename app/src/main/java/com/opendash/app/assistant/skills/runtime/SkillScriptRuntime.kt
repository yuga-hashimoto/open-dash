package com.opendash.app.assistant.skills.runtime

/**
 * Executes JS-style script blocks embedded in SKILL.md.
 *
 * Implementations must sandbox the script — no fs, net, or process access.
 * [QuickJsSkillScriptRuntime] is the real, always-available implementation
 * (QuickJS via cashapp/zipline).
 *
 * `read_memory` is a real, scoped bridge (see [SkillScriptContext.memory]):
 * the host pre-fetches exactly the memory entries a skill's frontmatter
 * declares under `memory_keys` and injects them before evaluation, since
 * plain `QuickJs.evaluate()` runs fully synchronously with no host-callback
 * capability — a script can't ask the host for a value mid-execution and
 * get a real answer back. `call_tool` has no bridge and isn't planned as a
 * simple follow-up: it would need either a live synchronous callback into
 * this evaluate() call (not exposed by cashapp/zipline's `QuickJs` — its
 * `initOutboundChannel`/`getInboundChannel` bridging plumbing is `internal`
 * to the zipline module and only reachable from the higher-level
 * `Zipline`/`ZiplineLoader` API, which targets precompiled Kotlin/JS
 * bundles, not raw script text) or a multi-step continuation protocol
 * (script requests one tool call via a sentinel return value, host executes
 * it, host re-runs the script from scratch with the result injected —
 * meaning scripts would need to be written in an idempotent/replayable
 * style, a real constraint on skill authors). Both are materially bigger
 * changes than the read-only bridge above, left for a dedicated follow-up.
 *
 * Callers MUST check `isAvailable()` before dispatching; [StubSkillScriptRuntime]
 * exists for builds that want to disable script execution entirely — it always
 * reports unavailable so the `run_skill_script` tool is not advertised to the LLM.
 */
interface SkillScriptRuntime {

    fun isAvailable(): Boolean

    suspend fun execute(
        script: SkillScript,
        context: SkillScriptContext
    ): SkillScriptResult
}

/**
 * Read-only payload handed to a script execution.
 *
 * [memory] is pre-fetched by [com.opendash.app.assistant.skills.SkillToolExecutor]
 * from the skill's declared `memory_keys` frontmatter *before* the script
 * runs — never the full memory store — so a script only ever sees what its
 * own SKILL.md explicitly asked for. Missing keys are simply absent from
 * the map rather than causing an error, since a memory entry not existing
 * yet is an expected, common case.
 */
data class SkillScriptContext(
    val input: String = "",
    val memory: Map<String, String> = emptyMap()
)

sealed interface SkillScriptResult {
    data class Success(val output: String) : SkillScriptResult
    data class Failure(val error: String) : SkillScriptResult
    data class NotAvailable(val reason: String) : SkillScriptResult
}
