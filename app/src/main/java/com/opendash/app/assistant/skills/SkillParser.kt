package com.opendash.app.assistant.skills

/**
 * Parses a SKILL.md file with YAML-like frontmatter:
 *
 * ---
 * name: skill-name
 * description: What the skill does
 * ---
 * # Instructions
 * (markdown body)
 *
 * Only `name` and `description` are required. `memory_keys` (comma-separated)
 * is optional and scopes what a skill's embedded JS may read via `read_memory`
 * — see [Skill.memoryKeys]. Other fields are ignored here.
 * The body is the content after the closing `---`.
 */
class SkillParser {

    fun parse(source: String, content: String): Skill? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("---")) return null

        val afterOpen = trimmed.removePrefix("---").trimStart('\n', '\r')
        val closeIdx = afterOpen.indexOf("\n---")
        if (closeIdx < 0) return null

        val frontmatter = afterOpen.substring(0, closeIdx)
        val body = afterOpen.substring(closeIdx + 4).trimStart('\n', '\r')

        val fields = parseFrontmatter(frontmatter)
        val name = fields["name"] ?: return null
        val description = fields["description"] ?: return null
        val memoryKeys = fields["memory_keys"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return Skill(
            name = name.trim(),
            description = description.trim(),
            body = body.trim(),
            source = source,
            memoryKeys = memoryKeys
        )
    }

    private fun parseFrontmatter(text: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim()
                .trim('"', '\'') // strip quotes
            if (key.isNotEmpty()) fields[key] = value
        }
        return fields
    }
}
