package com.opendash.app.assistant.skills.runtime

/**
 * Executes JS-style script blocks embedded in SKILL.md.
 *
 * Implementations must sandbox the script — no fs, net, or process access.
 * Tool invocations must go through an explicit bridge (not in the P19.1
 * skeleton; added when a real engine lands).
 *
 * Callers MUST check `isAvailable()` before dispatching; the default provider
 * is the [StubSkillScriptRuntime] that always reports unavailable so the
 * `run_skill_script` tool is not advertised to the LLM on stock builds.
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
