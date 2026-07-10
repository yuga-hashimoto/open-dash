package com.opendash.app.voice.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Fired by [AlarmManager] (via [AndroidAlarmScheduler]) when an alarm's
 * trigger time arrives. Deliberately thin and DB-free: hour/minute/
 * repeat-days-mask are carried directly in the [Intent] extras (set at
 * schedule time), so this receiver can both post the notification and
 * reschedule a recurring alarm's next occurrence with no database
 * access and no dependency on the app process being alive.
 */
class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_ID)
        val label = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_LABEL)
        val hour = intent.getIntExtra(AndroidAlarmScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AndroidAlarmScheduler.EXTRA_MINUTE, -1)
        val repeatDaysMask = intent.getIntExtra(AndroidAlarmScheduler.EXTRA_REPEAT_DAYS_MASK, 0)
        if (id == null || label == null || hour !in 0..23 || minute !in 0..59) {
            Timber.w("AlarmFireReceiver fired with missing/invalid extras")
            return
        }

        AlarmNotifier.notify(context, id, label, hour, minute)

        if (repeatDaysMask != 0) {
            val days = AlarmOccurrenceCalculator.maskToDays(repeatDaysMask)
            val nextTriggerMs = AlarmOccurrenceCalculator.nextTriggerMillis(LocalDateTime.now(), hour, minute, days)
            AndroidAlarmScheduler(context).schedule(id, label, hour, minute, repeatDaysMask, nextTriggerMs)
        }
    }
}
