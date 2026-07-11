package com.opendash.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists a Routine as a row. Actions are stored as a JSON string
 * to avoid nested Room relations for what is a small payload.
 */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val actionsJson: String,
    val updatedAtMs: Long,
    /** All three null means manual-invoke only (no schedule). */
    val scheduleHour: Int? = null,
    val scheduleMinute: Int? = null,
    val scheduleRepeatDaysMask: Int? = null
)
