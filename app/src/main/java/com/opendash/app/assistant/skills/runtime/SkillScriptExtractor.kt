package com.opendash.app.assistant.skills.runtime

import com.opendash.app.assistant.skills.Skill

/**
 * Extracts ```js / ```javascript fenced code blocks from a Skill's markdown body.
 *
 * Other languages (```kotlin, ```bash, ```yaml, etc.) are ignored — the runtime
 * only targets JavaScript so the surface area for sandbox escapes stays narrow.
 * Unknown languages and language-less fences are skipped silently.
 */
class SkillScriptExtractor {

    fun extract(skill: Skill): List<SkillScript> {
        val lines = skill.body.lines()
        val scripts = mutableListOf<SkillScript>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE_START.matchEntire(line.trim())
            if (fence == null) {
                i++
                continue
            }
            val lang = fence.groupValues[1].trim().lowercase()
            if (lang !in JS_LANGS) {
                i++
                continue
            }
            val bodyStart = i + 1
            var bodyEnd = bodyStart
            while (bodyEnd < lines.size && lines[bodyEnd].trim() != "```") {
                bodyEnd++
            }
            if (bodyEnd >= lines.size) {
                // Unclosed fence — treat as no script to avoid partial capture.
                break
            }
            val source = lines.subList(bodyStart, bodyEnd).joinToString("\n")
            if (source.isNotBlank()) {
                scripts.add(
                    SkillScript(
                        skillName = skill.name,
                        index = scripts.size,
                        language = lang,
                        source = source
                    )
                )
            }
            i = bodyEnd + 1
        }
        return scripts
    }

    companion object {
        private val FENCE_START = Regex("^```\\s*([A-Za-z0-9_+-]*)\\s*$")
        private val JS_LANGS = setOf("js", "javascript")
    }
}
