package com.opendash.app.assistant.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ToolArgumentCoercionTest {

    @Test
    fun `numeric string becomes Double`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("seconds" to "60"))
        assertThat(result["seconds"]).isEqualTo(60.0)
    }

    @Test
    fun `decimal string becomes Double`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("level" to "22.5"))
        assertThat(result["level"]).isEqualTo(22.5)
    }

    @Test
    fun `negative numeric string becomes Double`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("offset" to "-3"))
        assertThat(result["offset"]).isEqualTo(-3.0)
    }

    @Test
    fun `true and false strings become Boolean`() {
        val result = ToolArgumentCoercion.coerceMap(
            mapOf("a" to "true", "b" to "false")
        )
        assertThat(result["a"]).isEqualTo(true)
        assertThat(result["b"]).isEqualTo(false)
    }

    @Test
    fun `case-insensitive True and FALSE normalise`() {
        val result = ToolArgumentCoercion.coerceMap(
            mapOf("a" to "True", "b" to "FALSE")
        )
        assertThat(result["a"]).isEqualTo(true)
        assertThat(result["b"]).isEqualTo(false)
    }

    @Test
    fun `non-numeric string stays as string`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("location" to "Tokyo"))
        assertThat(result["location"]).isEqualTo("Tokyo")
    }

    @Test
    fun `empty string stays empty string`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("a" to ""))
        assertThat(result["a"]).isEqualTo("")
    }

    @Test
    fun `already-Number stays as original type`() {
        val result = ToolArgumentCoercion.coerceMap(
            mapOf("int" to 42, "double" to 3.14)
        )
        assertThat(result["int"]).isEqualTo(42)
        assertThat(result["double"]).isEqualTo(3.14)
    }

    @Test
    fun `already-Boolean stays as Boolean`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf("on" to true))
        assertThat(result["on"]).isEqualTo(true)
    }

    @Test
    fun `null stays null`() {
        val result = ToolArgumentCoercion.coerceMap(mapOf<String, Any?>("x" to null))
        assertThat(result["x"]).isNull()
    }

    @Test
    fun `numeric-looking strings with leading zeros preserved as string`() {
        // Phone numbers, zip codes etc look numeric but must not lose
        // their leading zero. Conservative rule: only coerce if the
        // round-trip (number.toString → input) matches.
        val result = ToolArgumentCoercion.coerceMap(mapOf("phone" to "0123456"))
        assertThat(result["phone"]).isEqualTo("0123456")
    }

    @Test
    fun `coercion preserves key order`() {
        val input = linkedMapOf<String, Any?>(
            "z" to "1",
            "a" to "true",
            "m" to "middle"
        )
        val result = ToolArgumentCoercion.coerceMap(input)
        assertThat(result.keys.toList()).containsExactly("z", "a", "m").inOrder()
    }

    @Test
    fun `whitespace-padded numeric strings are NOT coerced`() {
        // Preserve user intent — whitespace wrapping usually means it's
        // a free-form string, not a number.
        val result = ToolArgumentCoercion.coerceMap(mapOf("x" to " 42 "))
        assertThat(result["x"]).isEqualTo(" 42 ")
    }
}
