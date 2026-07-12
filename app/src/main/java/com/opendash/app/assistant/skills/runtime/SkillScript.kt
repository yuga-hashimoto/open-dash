package com.opendash.app.assistant.skills.runtime

/**
 * A single executable script block extracted from a SKILL.md body.
 *
 * SKILL.md can embed code fenced with ```js / ```javascript. The runtime
 * interprets these blocks in a sandbox when the LLM invokes the
 * `run_skill_script` tool.
 *
 * Executed by [QuickJsSkillScriptRuntime] (QuickJS via cashapp/zipline, P19.1).
 */
data class SkillScript(
    val skillName: String,
    val index: Int,
    val language: String,
    val source: String
)
