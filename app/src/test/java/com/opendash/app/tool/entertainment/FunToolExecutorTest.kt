package com.opendash.app.tool.entertainment

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FunToolExecutorTest {

    private lateinit var executor: FunToolExecutor

    @BeforeEach
    fun setup() {
        executor = FunToolExecutor(random = Random(42))
    }

    @Test
    fun `availableTools exposes all fun tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("tell_joke", "get_trivia", "fun_fact")
    }

    @Test
    fun `tell_joke returns a bundled joke`() = runTest {
        val result = executor.execute(ToolCall("1", "tell_joke", emptyMap()))

        assertThat(result.success).isTrue()
        val returnedJoke = BundledFunContent.JOKES.firstOrNull { result.data.contains(it) }
        assertThat(returnedJoke).isNotNull()
    }

    @Test
    fun `get_trivia returns a bundled question and answer`() = runTest {
        val result = executor.execute(ToolCall("2", "get_trivia", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"question\"")
        assertThat(result.data).contains("\"answer\"")
        val matched = BundledFunContent.TRIVIA.any {
            result.data.contains(it.question) && result.data.contains(it.answer)
        }
        assertThat(matched).isTrue()
    }

    @Test
    fun `fun_fact returns a bundled fact`() = runTest {
        val result = executor.execute(ToolCall("3", "fun_fact", emptyMap()))

        assertThat(result.success).isTrue()
        val returnedFact = BundledFunContent.FACTS.firstOrNull { result.data.contains(it) }
        assertThat(returnedFact).isNotNull()
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("4", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
