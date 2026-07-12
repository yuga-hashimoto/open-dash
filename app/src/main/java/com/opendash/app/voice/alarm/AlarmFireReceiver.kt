package com.opendash.app.voice.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opendash.app.data.db.AlarmDao
import com.opendash.app.service.VoiceService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Fired by [AlarmManager] (via [AndroidAlarmScheduler]) when an alarm's
 * trigger time arrives. Notification + recurring-alarm rescheduling are
 * hour/minute/repeat-days-mask are carried directly in the [Intent]
 * extras (set at schedule time), so those don't need database access.
 *
 * A fired one-shot alarm's [AlarmEntity][com.opendash.app.data.db.AlarmEntity]
 * row *does* need to be deleted here, though: without it, the row is
 * indistinguishable from a still-pending one-shot alarm (both are just
 * an hour/minute with an empty repeat mask), so
 * [com.opendash.app.service.BootRescheduler] would re-arm it as if it
 * were newly set on every subsequent reboot.
 *
 * The looping, fading alarm sound itself is started by
 * [VoiceService][com.opendash.app.service.VoiceService] rather than
 * from here: a [android.media.MediaPlayer] started inside
 * `onReceive()` wouldn't survive past the few seconds Android allows a
 * `BroadcastReceiver` to run before freezing the process, so this just
 * wakes the (already-foreground) service, which owns the live
 * [AlarmRingtoneController] singleton.
 */
@AndroidEntryPoint
class AlarmFireReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmDao: AlarmDao

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
        VoiceService.startWithAlarmRinging(context, id)

        if (repeatDaysMask != 0) {
            val days = AlarmOccurrenceCalculator.maskToDays(repeatDaysMask)
            val nextTriggerMs = AlarmOccurrenceCalculator.nextTriggerMillis(LocalDateTime.now(), hour, minute, days)
            AndroidAlarmScheduler(context).schedule(id, label, hour, minute, repeatDaysMask, nextTriggerMs)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmDao.delete(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete fired one-shot alarm $id")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
