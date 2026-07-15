package com.opendash.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KnowledgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnowledgeEntity)

    @Query("SELECT * FROM knowledge WHERE id = :id LIMIT 1")
    suspend fun get(id: String): KnowledgeEntity?

    @Query("SELECT * FROM knowledge ORDER BY createdAtMs ASC")
    suspend fun listAll(): List<KnowledgeEntity>

    @Query("DELETE FROM knowledge WHERE id = :id")
    suspend fun delete(id: String): Int
}
