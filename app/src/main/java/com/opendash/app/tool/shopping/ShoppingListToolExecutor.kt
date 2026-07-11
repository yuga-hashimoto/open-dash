package com.opendash.app.tool.shopping

import com.opendash.app.data.db.ShoppingListDao
import com.opendash.app.data.db.ShoppingListItemEntity
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import timber.log.Timber
import java.util.UUID

/**
 * Structured shopping/to-do list CRUD, distinct from the generic
 * `remember` key-value memory tool. Supports multiple named lists
 * (e.g. "shopping", "todo", or any user-chosen name).
 */
class ShoppingListToolExecutor(
    private val dao: ShoppingListDao
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "add_list_item",
            description = "Add an item to a named list (e.g. a shopping list or to-do list). Creates the list if it doesn't exist yet.",
            parameters = mapOf(
                "list_name" to ToolParameter("string", "Name of the list, e.g. 'shopping' or 'todo'", required = true),
                "item" to ToolParameter("string", "The item text to add", required = true)
            )
        ),
        ToolSchema(
            name = "remove_list_item",
            description = "Remove an item from a named list by its text.",
            parameters = mapOf(
                "list_name" to ToolParameter("string", "Name of the list", required = true),
                "item" to ToolParameter("string", "The item text to remove", required = true)
            )
        ),
        ToolSchema(
            name = "complete_item",
            description = "Mark an item on a list as done, or un-mark it.",
            parameters = mapOf(
                "list_name" to ToolParameter("string", "Name of the list", required = true),
                "item" to ToolParameter("string", "The item text to mark", required = true),
                "completed" to ToolParameter("boolean", "True to mark done, false to un-mark. Defaults to true.", required = false)
            )
        ),
        ToolSchema(
            name = "list_items",
            description = "List all items on a named list, including completion status.",
            parameters = mapOf(
                "list_name" to ToolParameter("string", "Name of the list", required = true)
            )
        ),
        ToolSchema(
            name = "clear_list",
            description = "Remove every item from a named list.",
            parameters = mapOf(
                "list_name" to ToolParameter("string", "Name of the list", required = true)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        return try {
            when (call.name) {
                "add_list_item" -> executeAdd(call)
                "remove_list_item" -> executeRemove(call)
                "complete_item" -> executeComplete(call)
                "list_items" -> executeList(call)
                "clear_list" -> executeClear(call)
                else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Shopping list tool failed")
            ToolResult(call.id, false, "", e.message ?: "List error")
        }
    }

    private suspend fun executeAdd(call: ToolCall): ToolResult {
        val listName = (call.arguments["list_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing list_name")
        val item = call.arguments["item"] as? String
            ?: return ToolResult(call.id, false, "", "Missing item")
        if (item.isBlank()) return ToolResult(call.id, false, "", "item must not be blank")

        dao.upsert(
            ShoppingListItemEntity(
                id = UUID.randomUUID().toString(),
                listName = listName,
                text = item,
                completed = false,
                createdAtMs = System.currentTimeMillis()
            )
        )
        return ToolResult(call.id, true, """{"list":"${listName.escapeJson()}","added":"${item.escapeJson()}"}""")
    }

    private suspend fun executeRemove(call: ToolCall): ToolResult {
        val listName = (call.arguments["list_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing list_name")
        val item = call.arguments["item"] as? String
            ?: return ToolResult(call.id, false, "", "Missing item")

        val found = dao.findByText(listName, item)
            ?: return ToolResult(call.id, false, "", "Item not found: $item")
        dao.deleteById(found.id)
        return ToolResult(call.id, true, """{"list":"${listName.escapeJson()}","removed":"${item.escapeJson()}"}""")
    }

    private suspend fun executeComplete(call: ToolCall): ToolResult {
        val listName = (call.arguments["list_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing list_name")
        val item = call.arguments["item"] as? String
            ?: return ToolResult(call.id, false, "", "Missing item")
        val completed = call.arguments["completed"] as? Boolean ?: true

        val found = dao.findByText(listName, item)
            ?: return ToolResult(call.id, false, "", "Item not found: $item")
        dao.setCompleted(found.id, completed)
        return ToolResult(
            call.id,
            true,
            """{"list":"${listName.escapeJson()}","item":"${item.escapeJson()}","completed":$completed}"""
        )
    }

    private suspend fun executeList(call: ToolCall): ToolResult {
        val listName = (call.arguments["list_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing list_name")
        val items = dao.listByName(listName)
        val data = items.joinToString(",") { i ->
            """{"text":"${i.text.escapeJson()}","completed":${i.completed}}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private suspend fun executeClear(call: ToolCall): ToolResult {
        val listName = (call.arguments["list_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ToolResult(call.id, false, "", "Missing list_name")
        val count = dao.clearList(listName)
        return ToolResult(call.id, true, """{"list":"${listName.escapeJson()}","cleared":$count}""")
    }

    private fun String.escapeJson(): String = buildString(length) {
        for (c in this@escapeJson) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
