package com.opendash.app.tool.shopping

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.ShoppingListDao
import com.opendash.app.data.db.ShoppingListItemEntity
import com.opendash.app.tool.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShoppingListToolExecutorTest {

    private lateinit var executor: ShoppingListToolExecutor
    private lateinit var dao: ShoppingListDao

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        executor = ShoppingListToolExecutor(dao)
    }

    @Test
    fun `availableTools exposes all list tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly(
            "add_list_item", "remove_list_item", "complete_item", "list_items", "clear_list"
        )
    }

    @Test
    fun `add_list_item persists a new item`() = runTest {
        val result = executor.execute(
            ToolCall("1", "add_list_item", mapOf("list_name" to "shopping", "item" to "milk"))
        )

        assertThat(result.success).isTrue()
        coVerify {
            dao.upsert(match { it.listName == "shopping" && it.text == "milk" && !it.completed })
        }
    }

    @Test
    fun `add_list_item missing item returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2", "add_list_item", mapOf("list_name" to "shopping"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `add_list_item blank item returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2b", "add_list_item", mapOf("list_name" to "shopping", "item" to "   "))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `remove_list_item deletes matching item by text`() = runTest {
        coEvery { dao.findByText("shopping", "milk") } returns
            ShoppingListItemEntity("id-1", "shopping", "milk", false, 1000L)

        val result = executor.execute(
            ToolCall("3", "remove_list_item", mapOf("list_name" to "shopping", "item" to "milk"))
        )

        assertThat(result.success).isTrue()
        coVerify { dao.deleteById("id-1") }
    }

    @Test
    fun `remove_list_item not found returns error`() = runTest {
        coEvery { dao.findByText("shopping", "eggs") } returns null

        val result = executor.execute(
            ToolCall("4", "remove_list_item", mapOf("list_name" to "shopping", "item" to "eggs"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `complete_item defaults to marking done`() = runTest {
        coEvery { dao.findByText("todo", "call mom") } returns
            ShoppingListItemEntity("id-2", "todo", "call mom", false, 1000L)

        val result = executor.execute(
            ToolCall("5", "complete_item", mapOf("list_name" to "todo", "item" to "call mom"))
        )

        assertThat(result.success).isTrue()
        coVerify { dao.setCompleted("id-2", true) }
    }

    @Test
    fun `complete_item with completed=false un-marks`() = runTest {
        coEvery { dao.findByText("todo", "call mom") } returns
            ShoppingListItemEntity("id-2", "todo", "call mom", true, 1000L)

        val result = executor.execute(
            ToolCall("6", "complete_item", mapOf("list_name" to "todo", "item" to "call mom", "completed" to false))
        )

        assertThat(result.success).isTrue()
        coVerify { dao.setCompleted("id-2", false) }
    }

    @Test
    fun `complete_item not found returns error`() = runTest {
        coEvery { dao.findByText("todo", "nope") } returns null

        val result = executor.execute(
            ToolCall("7", "complete_item", mapOf("list_name" to "todo", "item" to "nope"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `list_items returns items with completion status`() = runTest {
        coEvery { dao.listByName("shopping") } returns listOf(
            ShoppingListItemEntity("id-1", "shopping", "milk", false, 1000L),
            ShoppingListItemEntity("id-2", "shopping", "eggs", true, 2000L)
        )

        val result = executor.execute(
            ToolCall("8", "list_items", mapOf("list_name" to "shopping"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"text\":\"milk\"")
        assertThat(result.data).contains("\"completed\":false")
        assertThat(result.data).contains("\"text\":\"eggs\"")
        assertThat(result.data).contains("\"completed\":true")
    }

    @Test
    fun `list_items empty list returns empty array`() = runTest {
        coEvery { dao.listByName("todo") } returns emptyList()

        val result = executor.execute(
            ToolCall("9", "list_items", mapOf("list_name" to "todo"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("[]")
    }

    @Test
    fun `clear_list removes all items and reports count`() = runTest {
        coEvery { dao.clearList("shopping") } returns 3

        val result = executor.execute(
            ToolCall("10", "clear_list", mapOf("list_name" to "shopping"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"cleared\":3")
    }

    @Test
    fun `missing list_name returns error`() = runTest {
        val result = executor.execute(
            ToolCall("11", "list_items", emptyMap())
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `unknown tool name returns error`() = runTest {
        val result = executor.execute(
            ToolCall("12", "not_a_tool", emptyMap())
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `blank list_name returns error`() = runTest {
        val result = executor.execute(
            ToolCall("13", "add_list_item", mapOf("list_name" to "   ", "item" to "milk"))
        )

        assertThat(result.success).isFalse()
    }
}
