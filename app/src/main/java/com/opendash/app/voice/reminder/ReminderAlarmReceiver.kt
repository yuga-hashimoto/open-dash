package com.opendash.app.voice.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Fired by [AlarmManager] (via [AndroidReminderScheduler]) when a
 * reminder's trigger time arrives. Deliberately thin: the reminder
 * text is carried directly in the [Intent] extras (set at schedule
 * time), so this receiver needs no database access and works whether
 * or not the app process is alive. Posting logic lives in
 * [ReminderNotifier] so it's unit-testable independent of a real
 * [BroadcastReceiver] lifecycle.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AndroidReminderScheduler.EXTRA_ID)
        val text = intent.getStringExtra(AndroidReminderScheduler.EXTRA_TEXT)
        if (id == null || text == null) {
            Timber.w("ReminderAlarmReceiver fired without id/text extras")
            return
        }
        ReminderNotifier.notify(context, id, text)
    }
}
