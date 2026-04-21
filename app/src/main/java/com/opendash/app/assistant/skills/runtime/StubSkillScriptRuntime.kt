package com.opendash.app.assistant.skills.runtime

/**
 * Default runtime on stock OpenDash builds — reports unavailable so callers
 * know to degrade gracefully. A real engine (QuickJS / Hermes JNI) replaces
 * this binding once the native dependency is approved.
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
