package com.opendash.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An in-app, wall-clock alarm — recurring (day-of-week) or one-shot.
 * Distinct from the system-clock-app `set_alarm` tool
 * ([com.opendash.app.tool.system.SetAlarmToolExecutor]), which is
 * fire-and-forget and can't be listed/cancelled/snoozed by the app
 * afterward. [repeatDaysMask] is 0 for one-shot; otherwise a 7-bit mask
 * (bit 0 = Monday .. bit 6 = Sunday, see
 * [com.opendash.app.voice.alarm.AlarmOccurrenceCalculator]).
 */
@Entity(tableName = "alarm")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int,
    val label: String
)
