package com.opendash.app.assistant.skills

import com.opendash.app.assistant.skills.runtime.SkillScriptContext
import com.opendash.app.assistant.skills.runtime.SkillScriptExtractor
import com.opendash.app.assistant.skills.runtime.SkillScriptResult
import com.opendash.app.assistant.skills.runtime.SkillScriptRuntime
import com.opendash.app.data.db.MemoryDao
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema

/**
 * Exposes the skill registry as LLM-callable tools.
 *
 * - get_skill: fetch the full SKILL.md body to inline into the agent's context
 * - list_skills: list available skills (also visible via system prompt XML)
 * - install_skill_from_url: download + register a SKILL.md from a URL
 * - run_skill_script: execute an embedded JS script (P19.1) — only advertised
 *   when a real runtime is installed (stub keeps the tool hidden). When the
 *   skill declares `memory_keys` in its frontmatter, those entries are
 *   pre-fetched from [memoryDao] and handed to the script as `read_memory` —
 *   see [SkillScriptContext.memory] for why this is a real bridge despite
 *   the sandbox having no live host-callback mechanism.
 */
class SkillToolExecutor(
    private val registry: SkillRegistry,
    private val installer: SkillInstaller? = null,
    private val scriptRuntime: SkillScriptRuntime? = null,
    private val scriptExtractor: SkillScriptExtractor = SkillScriptExtractor(),
    private val memoryDao: MemoryDao? = null
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> {
        val tools = mutableListOf<ToolSchema>()
        if (!registry.isEmpty()) {
            tools.add(ToolSchema(
                name = "get_skill",
                description = "Load the full instructions for a skill. Use when your current task matches a skill listed in <available_skills>.",
                parameters = mapOf(
                    "name" to ToolParameter("string", "The skill name to load", required = true)
                )
            ))
            tools.add(ToolSchema(
                name = "list_skills",
                description = "List all available skills and their descriptions.",
                parameters = emptyMap()
            ))
        }
        if (installer != null) {
            tools.add(ToolSchema(
                name = "install_skill_from_url",
                description = "Download and register a new SKILL.md from a URL. The URL must point to a raw SKILL.md with YAML frontmatter (name, description).",
                parameters = mapOf(
                    "url" to ToolParameter("string", "Direct URL to SKILL.md content", required = true)
                )
            ))
        }
        if (scriptRuntime?.isAvailable() == true) {
            tools.add(ToolSchema(
                name = "run_skill_script",
                description = "Execute a JavaScript script block embedded in a SKILL.md body. Requires a skill with at least one ```js fenced block. If the skill declares memory_keys in its frontmatter, the script can read those specific memory entries via a read_memory(key) function — nothing else in memory is visible to it.",
                parameters = mapOf(
                    "skill" to ToolParameter("string", "Skill name", required = true),
                    "script_index" to ToolParameter("integer", "Zero-based index of the script block within the skill body", required = false),
                    "input" to ToolParameter("string", "Optional JSON string forwarded to the script", required = false)
                )
            ))
        }
        return tools
    }

    override suspend fun execute(call: ToolCall): ToolResult {
        return when (call.name) {
            "get_skill" -> executeGet(call)
            "list_skills" -> executeList(call)
            "install_skill_from_url" -> executeInstall(call)
            "run_skill_script" -> executeRunScript(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    }

    private fun executeGet(call: ToolCall): ToolResult {
        val name = call.arguments["name"] as? String
            ?: return ToolResult(call.id, false, "", "Missing name parameter")

        val skill = registry.get(name)
            ?: return ToolResult(call.id, false, "", "Skill not found: $name")

        val data = """{"name":"${skill.name.escapeJson()}","description":"${skill.description.escapeJson()}","body":"${skill.body.escapeJson()}"}"""
        return ToolResult(call.id, true, data)
    }

    private fun executeList(call: ToolCall): ToolResult {
        val items = registry.all().joinToString(",") { s ->
            """{"name":"${s.name.escapeJson()}","description":"${s.description.escapeJson()}"}"""
        }
        return ToolResult(call.id, true, "[$items]")
    }

    private suspend fun executeInstall(call: ToolCall): ToolResult {
        val url = call.arguments["url"] as? String
            ?: return ToolResult(call.id, false, "", "Missing url")
        val inst = installer
            ?: return ToolResult(call.id, false, "", "Skill installer not available")

        return when (val result = inst.installFromUrl(url)) {
            is SkillInstaller.Result.Installed -> ToolResult(
                call.id, true,
                """{"installed":"${result.skill.name.escapeJson()}","description":"${result.skill.description.escapeJson()}"}"""
            )
            is SkillInstaller.Result.Failed -> ToolResult(call.id, false, "", result.reason)
        }
    }

    private suspend fun executeRunScript(call: ToolCall): ToolResult {
        val runtime = scriptRuntime
            ?: return ToolResult(call.id, false, "", "Skill script runtime is not available on this build")
        if (!runtime.isAvailable()) {
            return ToolResult(call.id, false, "", "Skill script runtime is not available on this build")
        }

        val skillName = call.arguments["skill"] as? String
            ?: return ToolResult(call.id, false, "", "Missing skill")
        val skill = registry.get(skillName)
            ?: return ToolResult(call.id, false, "", "Skill not found: $skillName")

        val scripts = scriptExtractor.extract(skill)
        if (scripts.isEmpty()) {
            return ToolResult(call.id, false, "", "Skill has no embedded JavaScript blocks")
        }

        val requestedIndex = (call.arguments["script_index"] as? Number)?.toInt() ?: 0
        val script = scripts.getOrNull(requestedIndex)
            ?: return ToolResult(
                call.id,
                false,
                "",
                "script_index $requestedIndex out of range (skill has ${scripts.size} blocks)"
            )

        val context = SkillScriptContext(
            input = call.arguments["input"] as? String ?: "",
            memory = fetchMemory(skill)
        )

        return when (val result = runtime.execute(script, context)) {
            is SkillScriptResult.Success -> ToolResult(call.id, true, result.output)
            is SkillScriptResult.Failure -> ToolResult(call.id, false, "", result.error)
            is SkillScriptResult.NotAvailable -> ToolResult(call.id, false, "", result.reason)
        }
    }

    /**
     * Fetches exactly [Skill.memoryKeys] from [memoryDao] — never the whole
     * memory store, and never anything a skill didn't explicitly declare in
     * its own frontmatter. A key with no stored value is simply absent from
     * the result rather than an error.
     */
    private suspend fun fetchMemory(skill: Skill): Map<String, String> {
        val dao = memoryDao ?: return emptyMap()
        if (skill.memoryKeys.isEmpty()) return emptyMap()
        return skill.memoryKeys
            .mapNotNull { key -> dao.get(key)?.let { key to it.value } }
            .toMap()
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
