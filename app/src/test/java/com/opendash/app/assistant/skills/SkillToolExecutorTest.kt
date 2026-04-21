package com.opendash.app.assistant.skills

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.skills.runtime.SkillScript
import com.opendash.app.assistant.skills.runtime.SkillScriptContext
import com.opendash.app.assistant.skills.runtime.SkillScriptResult
import com.opendash.app.assistant.skills.runtime.SkillScriptRuntime
import com.opendash.app.tool.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillToolExecutorTest {

    private lateinit var registry: SkillRegistry
    private lateinit var executor: SkillToolExecutor

    @BeforeEach
    fun setup() {
        registry = SkillRegistry()
        executor = SkillToolExecutor(registry)
    }

    @Test
    fun `no tools exposed when registry is empty`() = runTest {
        val tools = executor.availableTools()
        assertThat(tools).isEmpty()
    }

    @Test
    fun `tools exposed when skills are registered`() = runTest {
        registry.register(Skill("s1", "d1", "body"))

        val tools = executor.availableTools()
        val names = tools.map { it.name }

        assertThat(names).containsExactly("get_skill", "list_skills")
    }

    @Test
    fun `get_skill returns full body`() = runTest {
        registry.register(Skill("weather", "Weather skill", "Do X then Y"))

        val result = executor.execute(
            ToolCall(id = "1", name = "get_skill", arguments = mapOf("name" to "weather"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Do X then Y")
        assertThat(result.data).contains("weather")
    }

    @Test
    fun `get_skill missing name returns error`() = runTest {
        val result = executor.execute(
            ToolCall(id = "2", name = "get_skill", arguments = emptyMap())
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `get_skill unknown name returns error`() = runTest {
        val result = executor.execute(
            ToolCall(id = "3", name = "get_skill", arguments = mapOf("name" to "missing"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("not found")
    }

    @Test
    fun `list_skills returns all registered`() = runTest {
        registry.register(Skill("a", "da", ""))
        registry.register(Skill("b", "db", ""))

        val result = executor.execute(
            ToolCall(id = "4", name = "list_skills", arguments = emptyMap())
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"a\"")
        assertThat(result.data).contains("\"b\"")
    }

    @Test
    fun `run_skill_script tool hidden when runtime is unavailable`() = runTest {
        registry.register(Skill("s", "d", "```js\nreturn 1;\n```"))
        val withStub = SkillToolExecutor(
            registry = registry,
            scriptRuntime = object : SkillScriptRuntime {
                override fun isAvailable() = false
                override suspend fun execute(
                    script: SkillScript,
                    context: SkillScriptContext
                ) = SkillScriptResult.NotAvailable("nope")
            }
        )

        val names = withStub.availableTools().map { it.name }
        assertThat(names).doesNotContain("run_skill_script")
    }

    @Test
    fun `run_skill_script advertised when runtime is available`() = runTest {
        registry.register(Skill("s", "d", "```js\nreturn 1;\n```"))
        val withRealRuntime = SkillToolExecutor(
            registry = registry,
            scriptRuntime = FakeAvailableRuntime()
        )

        val names = withRealRuntime.availableTools().map { it.name }
        assertThat(names).contains("run_skill_script")
    }

    @Test
    fun `run_skill_script executes first block when index omitted`() = runTest {
        val body = """
            ```js
            return 1;
            ```

            ```js
            return 2;
            ```
        """.trimIndent()
        registry.register(Skill("multi", "d", body))
        val runtime = FakeAvailableRuntime()
        val exec = SkillToolExecutor(registry, scriptRuntime = runtime)

        val result = exec.execute(
            ToolCall(id = "r", name = "run_skill_script", arguments = mapOf("skill" to "multi"))
        )

        assertThat(result.success).isTrue()
        assertThat(runtime.lastScript?.index).isEqualTo(0)
        assertThat(runtime.lastScript?.source).isEqualTo("return 1;")
    }

    @Test
    fun `run_skill_script forwards script_index and input`() = runTest {
        val body = """
            ```js
            return 1;
            ```

            ```js
            return 2;
            ```
        """.trimIndent()
        registry.register(Skill("multi", "d", body))
        val runtime = FakeAvailableRuntime()
        val exec = SkillToolExecutor(registry, scriptRuntime = runtime)

        val result = exec.execute(
            ToolCall(
                id = "r",
                name = "run_skill_script",
                arguments = mapOf(
                    "skill" to "multi",
                    "script_index" to 1,
                    "input" to "{\"x\":1}"
                )
            )
        )

        assertThat(result.success).isTrue()
        assertThat(runtime.lastScript?.index).isEqualTo(1)
        assertThat(runtime.lastScript?.source).isEqualTo("return 2;")
        assertThat(runtime.lastContext?.input).isEqualTo("{\"x\":1}")
    }

    @Test
    fun `run_skill_script out-of-range index returns error`() = runTest {
        registry.register(Skill("s", "d", "```js\nreturn 1;\n```"))
        val exec = SkillToolExecutor(registry, scriptRuntime = FakeAvailableRuntime())

        val result = exec.execute(
            ToolCall(
                id = "r",
                name = "run_skill_script",
                arguments = mapOf("skill" to "s", "script_index" to 5)
            )
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("out of range")
    }

    @Test
    fun `run_skill_script skill without scripts returns error`() = runTest {
        registry.register(Skill("s", "d", "just prose"))
        val exec = SkillToolExecutor(registry, scriptRuntime = FakeAvailableRuntime())

        val result = exec.execute(
            ToolCall(id = "r", name = "run_skill_script", arguments = mapOf("skill" to "s"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("no embedded JavaScript")
    }

    @Test
    fun `run_skill_script surfaces runtime failure`() = runTest {
        registry.register(Skill("s", "d", "```js\nthrow;\n```"))
        val runtime = FakeAvailableRuntime(
            result = SkillScriptResult.Failure("boom")
        )
        val exec = SkillToolExecutor(registry, scriptRuntime = runtime)

        val result = exec.execute(
            ToolCall(id = "r", name = "run_skill_script", arguments = mapOf("skill" to "s"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("boom")
    }

    private class FakeAvailableRuntime(
        private val result: SkillScriptResult = SkillScriptResult.Success("ok")
    ) : SkillScriptRuntime {
        var lastScript: SkillScript? = null
        var lastContext: SkillScriptContext? = null
        override fun isAvailable() = true
        override suspend fun execute(
            script: SkillScript,
            context: SkillScriptContext
        ): SkillScriptResult {
            lastScript = script
            lastContext = context
            return result
        }
    }
}
