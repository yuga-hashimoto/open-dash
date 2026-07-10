package com.opendash.app.voice.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opendash.app.service.VoiceService
import com.opendash.app.voice.alarm.AlarmOccurrenceCalculator
import timber.log.Timber
import java.time.LocalDateTime

/**
 * Fired by [AlarmManager] (via [AndroidRoutineScheduler]) when a
 * scheduled routine's trigger time arrives. Unlike
 * [com.opendash.app.voice.alarm.AlarmFireReceiver] (which just posts a
 * notification), running a routine means dispatching arbitrary tool
 * calls (lights, media, etc.) that need the live app's Hilt-provided
 * `ToolExecutor` — so this receiver starts [VoiceService] (which is
 * already the app's "keep the DI graph alive" anchor, per
 * [com.opendash.app.service.BootReceiver]'s existing pattern) with the
 * routine name to run, rather than acting on the tool graph itself.
 */
class RoutineFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AndroidRoutineScheduler.EXTRA_ROUTINE_ID)
        val name = intent.getStringExtra(AndroidRoutineScheduler.EXTRA_ROUTINE_NAME)
        val hour = intent.getIntExtra(AndroidRoutineScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AndroidRoutineScheduler.EXTRA_MINUTE, -1)
        val repeatDaysMask = intent.getIntExtra(AndroidRoutineScheduler.EXTRA_REPEAT_DAYS_MASK, 0)
        if (id == null || name == null || hour !in 0..23 || minute !in 0..59) {
            Timber.w("RoutineFireReceiver fired with missing/invalid extras")
            return
        }

        VoiceService.startWithRoutine(context, name)

        if (repeatDaysMask != 0) {
            val days = AlarmOccurrenceCalculator.maskToDays(repeatDaysMask)
            val nextTriggerMs = AlarmOccurrenceCalculator.nextTriggerMillis(LocalDateTime.now(), hour, minute, days)
            AndroidRoutineScheduler(context).schedule(id, name, hour, minute, repeatDaysMask, nextTriggerMs)
        }
    }
}
