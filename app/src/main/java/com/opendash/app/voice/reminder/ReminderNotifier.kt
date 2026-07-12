package com.opendash.app.voice.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opendash.app.MainActivity
import com.opendash.app.R
import timber.log.Timber

/**
 * Posts the heads-up notification for a fired reminder. Split out from
 * [ReminderAlarmReceiver] so the notification-building logic doesn't
 * require a real [BroadcastReceiver] lifecycle to exercise.
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "reminders_channel"

    fun notify(context: Context, id: String, text: String) {
        createChannel(context)

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("POST_NOTIFICATIONS not granted; reminder fired silently for $id")
            return
        }
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "One-time reminder alerts"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
