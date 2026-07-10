package com.opendash.app.voice.fastpath

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TellJokeMatcherTest {

    private fun match(s: String): FastPathMatch? = TellJokeMatcher.tryMatch(s.lowercase().trim())

    @Test
    fun `english tell me a joke matches`() {
        assertThat(match("tell me a joke")?.toolName).isEqualTo("tell_joke")
    }

    @Test
    fun `english tell a joke matches`() {
        assertThat(match("tell a joke")?.toolName).isEqualTo("tell_joke")
    }

    @Test
    fun `english say something funny matches`() {
        assertThat(match("say something funny")?.toolName).isEqualTo("tell_joke")
    }

    @Test
    fun `japanese joke request matches`() {
        assertThat(match("ジョークを言って")?.toolName).isEqualTo("tell_joke")
    }

    @Test
    fun `japanese omoshiroi hanashi matches`() {
        assertThat(match("面白い話をして")?.toolName).isEqualTo("tell_joke")
    }

    @Test
    fun `unrelated utterances do not match`() {
        assertThat(match("what time is it")).isNull()
        assertThat(match("tell me the weather")).isNull()
    }
}
