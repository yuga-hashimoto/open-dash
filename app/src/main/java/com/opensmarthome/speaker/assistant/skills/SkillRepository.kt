package com.opensmarthome.speaker.assistant.skills

import java.io.File

/**
 * UI-facing repository for managing installed skills.
 * Bundled skills (from assets) are read-only; only user-installed skills can be deleted.
 */
class SkillRepository(
    private val registry: SkillRegistry,
    private val userSkillsDir: File
) {

    data class SkillView(
        val name: String,
        val description: String,
        val source: String,
        val deletable: Boolean
    )

    fun listAll(): List<SkillView> =
        registry.all().map { skill ->
            SkillView(
                name = skill.name,
                description = skill.description,
                source = skill.source,
                deletable = skill.source.startsWith("installed:") ||
                    skill.source.startsWith("file:${userSkillsDir.absolutePath}")
            )
        }

    /**
     * Delete an installed user skill. Cannot delete bundled skills.
     * Returns true if deletion happened.
     */
    fun delete(name: String): Boolean {
        val skill = registry.get(name) ?: return false
        if (!isDeletable(skill)) return false

        // Try to find and delete the backing file
        val subdir = File(userSkillsDir, name.toSlug())
        if (subdir.exists() && subdir.isDirectory) {
            subdir.deleteRecursively()
        }

        // Remove from registry
        registry.unregister(name)
        return true
    }

    private fun isDeletable(skill: Skill): Boolean =
        skill.source.startsWith("installed:") ||
            skill.source.startsWith("file:${userSkillsDir.absolutePath}")

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')
}
