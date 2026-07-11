package com.opendash.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-time, time-triggered reminder, distinct from calendar events
 * (no attendees/location/recurrence) and from timers (wall-clock time,
 * not a countdown duration). Rows are never marked "fired" — instead
 * [ReminderDao.listUpcoming] filters by `triggerAtMs > now`, so a fired
 * reminder simply stops appearing without needing a write-back from the
 * alarm receiver (which only carries the notification text/id, no DB
 * access).
 */
@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val text: String,
    val triggerAtMs: Long,
    val createdAtMs: Long
)
