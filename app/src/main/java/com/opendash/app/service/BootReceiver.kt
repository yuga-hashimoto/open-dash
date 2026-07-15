package com.opendash.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opendash.app.assistant.routine.RoomRoutineStore
import com.opendash.app.data.db.AlarmDao
import com.opendash.app.data.db.ReminderDao
import com.opendash.app.data.db.RoutineDao
import com.opendash.app.voice.alarm.AndroidAlarmScheduler
import com.opendash.app.voice.reminder.AndroidReminderScheduler
import com.opendash.app.voice.routine.AndroidRoutineScheduler
import com.squareup.moshi.Moshi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmDao: AlarmDao
    @Inject lateinit var reminderDao: ReminderDao
    @Inject lateinit var routineDao: RoutineDao
    @Inject lateinit var moshi: Moshi

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val decision = BootPolicy.onBootCompleted()
        Timber.d(
            "Boot completed: startMicFgs=%s reschedule=%s",
            decision.startMicrophoneForegroundService,
            decision.rescheduleAlarmsRemindersRoutines,
        )

        // targetSdk 35: microphone FGS must not start from BOOT_COMPLETED.
        // MainActivity starts VoiceService from a user-visible activity.
        if (decision.startMicrophoneForegroundService) {
            VoiceService.start(context)
        }

        if (!decision.rescheduleAlarmsRemindersRoutines) return

        // Exact alarms don't survive reboot — re-arm every persisted
        // alarm/reminder/scheduled routine. goAsync() extends the
        // receiver's lifetime past onReceive() returning, since this
        // does real DB + AlarmManager work.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BootRescheduler(
                    alarmDao = alarmDao,
                    reminderDao = reminderDao,
                    routineStore = RoomRoutineStore(routineDao, moshi),
                    alarmScheduler = AndroidAlarmScheduler(context),
                    reminderScheduler = AndroidReminderScheduler(context),
                    routineScheduler = AndroidRoutineScheduler(context)
                ).rescheduleAll()
            } catch (e: Exception) {
                Timber.e(e, "Boot rescheduling failed")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
