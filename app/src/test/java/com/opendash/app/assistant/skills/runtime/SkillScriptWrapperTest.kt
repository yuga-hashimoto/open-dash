package com.opendash.app.assistant.skills.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SkillScriptWrapperTest {

    @Test
    fun `embeds the input string as a JS literal the script can read`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return input;", input = "hello")

        assertThat(wrapped).contains("var input = \"hello\";")
    }

    @Test
    fun `escapes quotes and backslashes in input so the literal stays valid`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return input;", input = """say "hi"\now""")

        assertThat(wrapped).contains("""var input = "say \"hi\"\\now";""")
    }

    @Test
    fun `escapes newlines in input`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return input;", input = "line1\nline2")

        assertThat(wrapped).contains("var input = \"line1\\nline2\";")
        assertThat(wrapped).doesNotContain("line1\nline2\"")
    }

    @Test
    fun `escapes line and paragraph separators`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return input;", input = "a b c")

        assertThat(wrapped).contains("a\\u2028b\\u2029c")
    }

    @Test
    fun `wraps script source inside an IIFE that stringifies object returns`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return { ok: true };", input = "")

        assertThat(wrapped).contains("return { ok: true };")
        assertThat(wrapped).contains("JSON.stringify(__skillResult)")
    }

    @Test
    fun `normalizes undefined and null results to an empty string`() {
        val wrapped = SkillScriptWrapper.wrap(source = "// no return", input = "")

        assertThat(wrapped).contains(
            "if (__skillResult === undefined || __skillResult === null) return \"\";"
        )
    }

    @Test
    fun `defaults to an empty memory object when none is supplied`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return 1;", input = "")

        assertThat(wrapped).contains("var __memory = {};")
    }

    @Test
    fun `embeds memory entries as an escaped JS object literal`() {
        val wrapped = SkillScriptWrapper.wrap(
            source = "return read_memory('favorite_color');",
            input = "",
            memory = mapOf("favorite_color" to "blue", "favorite_food" to "sushi")
        )

        assertThat(wrapped).contains(
            "var __memory = {\"favorite_color\":\"blue\",\"favorite_food\":\"sushi\"};"
        )
    }

    @Test
    fun `escapes quotes inside memory keys and values`() {
        val wrapped = SkillScriptWrapper.wrap(
            source = "return 1;",
            input = "",
            memory = mapOf("""say "hi"""" to """quoted "value"""")
        )

        assertThat(wrapped).contains(""""say \"hi\"":"quoted \"value\""""")
    }

    @Test
    fun `defines read_memory as a function that falls back to null for missing keys`() {
        val wrapped = SkillScriptWrapper.wrap(source = "return 1;", input = "")

        assertThat(wrapped).contains("function read_memory(key)")
        assertThat(wrapped).contains("__memory[key] : null")
    }
}
