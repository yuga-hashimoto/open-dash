package com.opendash.app.service

/**
 * Pure policy for [android.content.Intent.ACTION_BOOT_COMPLETED].
 *
 * Apps targeting Android 14+ (targetSdk ≥ 34) cannot start a microphone-type
 * foreground service from BOOT_COMPLETED. OpenDash targets SDK 35, so boot
 * must only re-arm exact alarms/reminders/routines; [VoiceService] starts later
 * from a user-visible activity context (MainActivity).
 *
 * @see <a href="https://developer.android.com/about/versions/15/changes/foreground-service-types">FGS type restrictions</a>
 */
object BootPolicy {

    data class Decision(
        val startMicrophoneForegroundService: Boolean,
        val rescheduleAlarmsRemindersRoutines: Boolean,
    )

    fun onBootCompleted(): Decision = Decision(
        startMicrophoneForegroundService = false,
        rescheduleAlarmsRemindersRoutines = true,
    )
}
