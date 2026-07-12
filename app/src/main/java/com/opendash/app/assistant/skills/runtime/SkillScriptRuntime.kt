package com.opendash.app.assistant.skills.runtime

/**
 * Executes JS-style script blocks embedded in SKILL.md.
 *
 * Implementations must sandbox the script — no fs, net, or process access.
 * [QuickJsSkillScriptRuntime] is the real, always-available implementation
 * (QuickJS via cashapp/zipline). Tool invocations (`call_tool`, `read_memory`)
 * still have no bridge — scripts only see [SkillScriptContext.input] — since
 * that's a separate, security-sensitive capability surface left for a
 * dedicated follow-up rather than the engine swap itself.
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
 * Read-only payload handed to a script execution. Intentionally minimal —
 * the sandbox gets only what it needs to produce output. Tool API bridging
 * (call_tool, read_memory, etc.) will be added as a follow-up once a real
 * runtime is wired.
 */
data class SkillScriptContext(
    val input: String = ""
)

sealed interface SkillScriptResult {
    data class Success(val output: String) : SkillScriptResult
    data class Failure(val error: String) : SkillScriptResult
    data class NotAvailable(val reason: String) : SkillScriptResult
}
