package com.opendash.app.assistant.skills

/**
 * A SKILL.md file provides task-specific instructions to the agent.
 *
 * Inspired by OpenClaw's skills system (src/agents/skills/).
 * Skills are registered in the system prompt as <available_skills> XML,
 * and the agent loads the full SKILL.md body on demand when the task matches.
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val source: String = "bundled",
    /**
     * Optional `memory_keys` frontmatter field (comma-separated) — the exact
     * set of memory keys this skill's embedded JS may read via `read_memory`
     * (P19.1's follow-up bridge). Empty means the skill's scripts have no
     * memory access at all; there is no wildcard/"read everything" option.
     */
    val memoryKeys: List<String> = emptyList()
)
