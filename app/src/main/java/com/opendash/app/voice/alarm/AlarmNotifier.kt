package com.opendash.app.voice.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opendash.app.MainActivity
import com.opendash.app.R

/**
 * Posts the heads-up notification for a fired alarm. Split out from
 * [AlarmFireReceiver] so the notification-building logic doesn't
 * require a real [android.content.BroadcastReceiver] lifecycle to
 * exercise.
 *
 * The alarm sound plays via the notification channel's own configured
 * sound (TYPE_ALARM ringtone, USAGE_ALARM audio attributes) rather than
 * a continuously-looping foreground-service [android.media.MediaPlayer]
 * — this device is voice-first, so "snooze"/"cancel my alarm" is spoken
 * to [com.opendash.app.tool.alarm.AlarmToolExecutor] rather than tapped
 * on a notification action button, and a single alert tone plus a
 * heads-up notification is enough to get the user's attention without
 * the added complexity of a foreground service.
 */
object AlarmNotifier {

    private const val CHANNEL_ID = "alarms_channel"

    fun notify(context: Context, id: String, label: String, hour: Int, minute: Int) {
        createChannel(context)

        val displayLabel = label.ifBlank { "%02d:%02d".format(hour, minute) }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(displayLabel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "In-app alarm alerts"
            if (soundUri != null) {
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
