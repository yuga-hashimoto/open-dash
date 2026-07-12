package com.opendash.app.assistant.skills.runtime

/**
 * Reports unavailable so callers degrade gracefully — kept as an option for a
 * build variant that wants `run_skill_script` disabled entirely. The default
 * Hilt binding is [QuickJsSkillScriptRuntime] as of P19.1.
 */
class StubSkillScriptRuntime : SkillScriptRuntime {

    override fun isAvailable(): Boolean = false

    override suspend fun execute(
        script: SkillScript,
        context: SkillScriptContext
    ): SkillScriptResult = SkillScriptResult.NotAvailable(
        reason = "Skill script runtime is not installed on this build. " +
            "Update OpenDash or install a skill-runtime extension to run embedded JavaScript."
    )
}
