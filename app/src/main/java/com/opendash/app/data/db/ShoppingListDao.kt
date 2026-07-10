package com.opendash.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ShoppingListDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShoppingListItemEntity)

    @Query("SELECT * FROM shopping_list_item WHERE listName = :listName COLLATE NOCASE ORDER BY createdAtMs ASC")
    suspend fun listByName(listName: String): List<ShoppingListItemEntity>

    @Query("SELECT * FROM shopping_list_item WHERE listName = :listName COLLATE NOCASE AND text = :text COLLATE NOCASE LIMIT 1")
    suspend fun findByText(listName: String, text: String): ShoppingListItemEntity?

    @Query("UPDATE shopping_list_item SET completed = :completed WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean): Int

    @Query("DELETE FROM shopping_list_item WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM shopping_list_item WHERE listName = :listName COLLATE NOCASE")
    suspend fun clearList(listName: String): Int
}
