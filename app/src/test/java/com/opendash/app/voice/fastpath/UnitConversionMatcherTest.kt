package com.opendash.app.voice.fastpath

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UnitConversionMatcherTest {

    private fun match(s: String): FastPathMatch? = UnitConversionMatcher.tryMatch(s.lowercase().trim())

    @Test
    fun `convert prefix form matches`() {
        val result = match("convert 5 km to miles")
        assertThat(result?.toolName).isEqualTo("convert_units")
        assertThat(result?.arguments?.get("value")).isEqualTo(5.0)
        assertThat(result?.arguments?.get("from")).isEqualTo("km")
        assertThat(result?.arguments?.get("to")).isEqualTo("miles")
    }

    @Test
    fun `bare X to Y form matches`() {
        val result = match("5 kilometers to miles")
        assertThat(result?.arguments?.get("value")).isEqualTo(5.0)
        assertThat(result?.arguments?.get("from")).isEqualTo("kilometers")
        assertThat(result?.arguments?.get("to")).isEqualTo("miles")
    }

    @Test
    fun `how many form matches with value and units swapped`() {
        val result = match("how many miles is 5 km")
        assertThat(result?.arguments?.get("value")).isEqualTo(5.0)
        assertThat(result?.arguments?.get("from")).isEqualTo("km")
        assertThat(result?.arguments?.get("to")).isEqualTo("miles")
    }

    @Test
    fun `decimal value matches`() {
        val result = match("convert 2.5 kg to lb")
        assertThat(result?.arguments?.get("value")).isEqualTo(2.5)
    }

    @Test
    fun `unrelated utterances do not match`() {
        assertThat(match("what time is it")).isNull()
        assertThat(match("turn off the lights")).isNull()
    }

    @Test
    fun `unknown unit words still route through (validation deferred to the tool)`() {
        // The matcher doesn't validate unit names — UnitConverterToolExecutor
        // returns an UnknownUnit error at execution time. Confirms the
        // matcher doesn't crash or silently drop unrecognized units.
        val result = match("convert 5 bananas to oranges")
        assertThat(result?.toolName).isEqualTo("convert_units")
    }
}
