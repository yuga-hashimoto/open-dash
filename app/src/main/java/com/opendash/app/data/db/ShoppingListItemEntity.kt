package com.opendash.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single item on a named list (shopping, todo, or any user-chosen
 * list name). Distinct from the generic key-value `memory` table so
 * lists get real CRUD (add/remove/complete/clear) instead of prose
 * key lookups.
 */
@Entity(tableName = "shopping_list_item")
data class ShoppingListItemEntity(
    @PrimaryKey val id: String,
    val listName: String,
    val text: String,
    val completed: Boolean,
    val createdAtMs: Long
)
