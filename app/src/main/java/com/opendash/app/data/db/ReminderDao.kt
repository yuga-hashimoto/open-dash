package com.opendash.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("SELECT * FROM reminder WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE triggerAtMs > :nowMs ORDER BY triggerAtMs ASC")
    suspend fun listUpcoming(nowMs: Long): List<ReminderEntity>

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: String): Int
}
