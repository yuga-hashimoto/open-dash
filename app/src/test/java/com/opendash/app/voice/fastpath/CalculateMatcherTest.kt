package com.opendash.app.voice.fastpath

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CalculateMatcherTest {

    private fun match(s: String): FastPathMatch? = CalculateMatcher.tryMatch(s.lowercase().trim())

    @Test
    fun `english plus matches`() {
        val result = match("what is 5 plus 3")
        assertThat(result?.toolName).isEqualTo("calculate")
        assertThat(result?.arguments?.get("expression")).isEqualTo("5 + 3")
    }

    @Test
    fun `english bare operand matches without prefix`() {
        val result = match("5 plus 3")
        assertThat(result?.arguments?.get("expression")).isEqualTo("5 + 3")
    }

    @Test
    fun `english minus matches`() {
        val result = match("what's 10 minus 4")
        assertThat(result?.arguments?.get("expression")).isEqualTo("10 - 4")
    }

    @Test
    fun `english times matches`() {
        val result = match("calculate 6 times 7")
        assertThat(result?.arguments?.get("expression")).isEqualTo("6 * 7")
    }

    @Test
    fun `english multiplied by matches`() {
        val result = match("what is 6 multiplied by 7")
        assertThat(result?.arguments?.get("expression")).isEqualTo("6 * 7")
    }

    @Test
    fun `english divided by matches`() {
        val result = match("12 divided by 4")
        assertThat(result?.arguments?.get("expression")).isEqualTo("12 / 4")
    }

    @Test
    fun `decimal operands match`() {
        val result = match("2.5 plus 3.5")
        assertThat(result?.arguments?.get("expression")).isEqualTo("2.5 + 3.5")
    }

    @Test
    fun `japanese tasu matches`() {
        val result = match("5たす3")
        assertThat(result?.arguments?.get("expression")).isEqualTo("5 + 3")
    }

    @Test
    fun `japanese hiku matches`() {
        val result = match("10ひく4")
        assertThat(result?.arguments?.get("expression")).isEqualTo("10 - 4")
    }

    @Test
    fun `japanese kakeru matches`() {
        val result = match("6かける7")
        assertThat(result?.arguments?.get("expression")).isEqualTo("6 * 7")
    }

    @Test
    fun `japanese waru matches`() {
        val result = match("12わる4")
        assertThat(result?.arguments?.get("expression")).isEqualTo("12 / 4")
    }

    @Test
    fun `unrelated utterances do not match`() {
        assertThat(match("what time is it")).isNull()
        assertThat(match("set a timer for five minutes")).isNull()
        assertThat(match("5 apples plus 3 oranges please")).isNull()
    }

    @Test
    fun `compound expressions fall through to LLM`() {
        assertThat(match("what is 5 plus 3 times 2")).isNull()
        assertThat(match("sqrt(16) plus 2")).isNull()
    }
}
