package com.opensmarthome.speaker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import timber.log.Timber

class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Timber.d("Media button pressed, triggering voice input")
                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    action = VoiceService.ACTION_START_LISTENING
                }
                context.startService(serviceIntent)
            }
        }
    }
}
