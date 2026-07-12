package com.opendash.app.tool

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class JsonEscapeTest {

    @Test
    fun `escapes backslash and double quote`() {
        assertThat("""a\b"c""".escapeJson()).isEqualTo("""a\\b\"c""")
    }

    @Test
    fun `escapes common control characters`() {
        assertThat("a\bb".escapeJson()).isEqualTo("a\\bb")
        assertThat("ab".escapeJson()).isEqualTo("a\\fb")
        assertThat("a\nb".escapeJson()).isEqualTo("a\\nb")
        assertThat("a\rb".escapeJson()).isEqualTo("a\\rb")
        assertThat("a\tb".escapeJson()).isEqualTo("a\\tb")
    }

    @Test
    fun `escapes other control characters as unicode escapes`() {
        assertThat("ab".escapeJson()).isEqualTo("a\\u0001b")
    }

    @Test
    fun `leaves ordinary text untouched`() {
        assertThat("Bohemian Rhapsody".escapeJson()).isEqualTo("Bohemian Rhapsody")
    }

    @Test
    fun `empty string escapes to empty string`() {
        assertThat("".escapeJson()).isEqualTo("")
    }
}
