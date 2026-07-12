package com.opendash.app.voice.alarm

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
 * Posts the heads-up / full-screen notification for a fired alarm.
 * Split out from [AlarmFireReceiver] so the notification-building logic
 * doesn't require a real [android.content.BroadcastReceiver] lifecycle
 * to exercise.
 *
 * The channel itself carries no sound — [AlarmRingtoneController]
 * (started via [com.opendash.app.service.VoiceService]) owns the actual
 * looping, fading alarm tone, so a silent channel avoids a jarring
 * double-sound (channel ding + ramping loop) on top of it. This
 * notification's job is visual: [NotificationCompat.Builder.setFullScreenIntent]
 * surfaces it even over a locked/idle screen, same as a phone's native
 * alarm clock, while "snooze"/"cancel my alarm" stays spoken —
 * voice-first — to [com.opendash.app.tool.alarm.AlarmToolExecutor]
 * rather than a notification action button.
 *
 * The notification is visual only — [AlarmRingtoneController]'s ringing
 * sound is triggered separately by [AlarmFireReceiver] and does not
 * depend on `POST_NOTIFICATIONS` being granted, so a denied permission
 * silences the full-screen visual but not the actual alarm sound.
 */
object AlarmNotifier {

    // v2: a NotificationChannel's sound is immutable after first creation
    // on a given device, so silencing it (now that AlarmRingtoneController
    // owns the tone) needs a new channel id — reusing "alarms_channel"
    // would leave upgrading installs stuck with their old channel sound
    // playing alongside the new looping fade.
    private const val CHANNEL_ID = "alarms_channel_v2"

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
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("POST_NOTIFICATIONS not granted; alarm $id will ring without a visual notification")
            return
        }
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "In-app alarm alerts"
            setSound(null, null)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
