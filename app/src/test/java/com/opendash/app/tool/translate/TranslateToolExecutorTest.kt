package com.opendash.app.tool.translate

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TranslateToolExecutorTest {

    private lateinit var engine: TranslateEngine
    private lateinit var executor: TranslateToolExecutor

    @BeforeEach
    fun setup() {
        engine = mockk()
        executor = TranslateToolExecutor(engine)
    }

    @Test
    fun `availableTools exposes translate_text`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("translate_text")
    }

    @Test
    fun `translate_text returns translated text on success`() = runTest {
        coEvery { engine.translate("hello", "en", "ja") } returns TranslateResult.Translated("こんにちは")

        val result = executor.execute(
            ToolCall("1", "translate_text", mapOf("text" to "hello", "source_language" to "en", "target_language" to "ja"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"translated_text\":\"こんにちは\"")
    }

    @Test
    fun `translate_text missing text returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2", "translate_text", mapOf("source_language" to "en", "target_language" to "ja"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `translate_text blank text returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2b", "translate_text", mapOf("text" to "   ", "source_language" to "en", "target_language" to "ja"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `translate_text missing source_language returns error`() = runTest {
        val result = executor.execute(
            ToolCall("3", "translate_text", mapOf("text" to "hello", "target_language" to "ja"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `translate_text missing target_language returns error`() = runTest {
        val result = executor.execute(
            ToolCall("4", "translate_text", mapOf("text" to "hello", "source_language" to "en"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `translate_text unsupported language returns error`() = runTest {
        coEvery { engine.translate("hello", "xx", "ja") } returns TranslateResult.UnsupportedLanguage("xx")

        val result = executor.execute(
            ToolCall("5", "translate_text", mapOf("text" to "hello", "source_language" to "xx", "target_language" to "ja"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `translate_text engine failure returns error`() = runTest {
        coEvery { engine.translate("hello", "en", "ja") } returns TranslateResult.Failed("model download failed")

        val result = executor.execute(
            ToolCall("6", "translate_text", mapOf("text" to "hello", "source_language" to "en", "target_language" to "ja"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("7", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
