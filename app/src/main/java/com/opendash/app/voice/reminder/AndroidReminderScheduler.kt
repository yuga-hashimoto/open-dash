package com.opendash.app.voice.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Production [ReminderScheduler] backed by [AlarmManager]. Uses
 * `setExactAndAllowWhileIdle` so the reminder still fires from Doze
 * mode, and carries the reminder text directly in the [Intent] extras
 * so [ReminderAlarmReceiver] can post the notification without any
 * database access at fire-time.
 *
 * The request code is derived from [id]'s hash so `cancel` can rebuild
 * an equal [PendingIntent] (same action, same request code) without
 * having to look anything up.
 */
class AndroidReminderScheduler(
    private val context: Context
) : ReminderScheduler {

    override fun schedule(id: String, text: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            buildPendingIntent(id, text)
        )
    }

    override fun cancel(id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(id, text = null))
    }

    private fun buildPendingIntent(id: String, text: String?): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER_FIRE
            putExtra(EXTRA_ID, id)
            if (text != null) putExtra(EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_REMINDER_FIRE = "com.opendash.app.action.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TEXT = "reminder_text"
    }
}
