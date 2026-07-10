package com.opendash.app.voice.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Production [AlarmScheduler] backed by [AlarmManager]. Uses
 * `setExactAndAllowWhileIdle` so the alarm still fires from Doze mode,
 * and carries everything [AlarmFireReceiver] needs directly in the
 * [Intent] extras — including [hour]/[minute]/[repeatDaysMask] — so a
 * recurring alarm can compute and reschedule its own next occurrence
 * without any database access at fire-time.
 */
class AndroidAlarmScheduler(
    private val context: Context
) : AlarmScheduler {

    override fun schedule(id: String, label: String, hour: Int, minute: Int, repeatDaysMask: Int, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmFireReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_REPEAT_DAYS_MASK, repeatDaysMask)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
    }

    override fun cancel(id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmFireReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    companion object {
        const val ACTION_ALARM_FIRE = "com.opendash.app.action.ALARM_FIRE"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_LABEL = "alarm_label"
        const val EXTRA_HOUR = "alarm_hour"
        const val EXTRA_MINUTE = "alarm_minute"
        const val EXTRA_REPEAT_DAYS_MASK = "alarm_repeat_days_mask"
    }
}
