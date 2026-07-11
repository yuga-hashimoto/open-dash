package com.opendash.app.voice.fastpath

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AddShoppingListItemMatcherTest {

    private fun match(s: String): FastPathMatch? = AddShoppingListItemMatcher.tryMatch(s.lowercase().trim())

    @Test
    fun `add X to my shopping list matches`() {
        val result = match("add milk to my shopping list")
        assertThat(result?.toolName).isEqualTo("add_list_item")
        assertThat(result?.arguments?.get("item")).isEqualTo("milk")
        assertThat(result?.arguments?.get("list_name")).isEqualTo("shopping")
    }

    @Test
    fun `add X to the shopping list matches`() {
        val result = match("add eggs to the shopping list")
        assertThat(result?.arguments?.get("item")).isEqualTo("eggs")
        assertThat(result?.arguments?.get("list_name")).isEqualTo("shopping")
    }

    @Test
    fun `add X to my todo list matches`() {
        val result = match("add call mom to my todo list")
        assertThat(result?.arguments?.get("item")).isEqualTo("call mom")
        assertThat(result?.arguments?.get("list_name")).isEqualTo("todo")
    }

    @Test
    fun `add X to my to-do list matches`() {
        val result = match("add call mom to my to-do list")
        assertThat(result?.arguments?.get("item")).isEqualTo("call mom")
        assertThat(result?.arguments?.get("list_name")).isEqualTo("todo")
    }

    @Test
    fun `japanese shopping list add matches`() {
        val result = match("買い物リストに牛乳を追加して")
        assertThat(result?.toolName).isEqualTo("add_list_item")
        assertThat(result?.arguments?.get("item")).isEqualTo("牛乳")
        assertThat(result?.arguments?.get("list_name")).isEqualTo("shopping")
    }

    @Test
    fun `unrelated utterances do not match`() {
        assertThat(match("what time is it")).isNull()
        assertThat(match("add an event to my calendar")).isNull()
        assertThat(match("turn on the lights")).isNull()
    }
}
