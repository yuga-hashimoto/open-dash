package com.opendash.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlarmDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: AlarmEntity)

    @Query("SELECT * FROM alarm WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AlarmEntity?

    @Query("SELECT * FROM alarm ORDER BY hour ASC, minute ASC")
    suspend fun listAll(): List<AlarmEntity>

    @Query("DELETE FROM alarm WHERE id = :id")
    suspend fun delete(id: String): Int
}
