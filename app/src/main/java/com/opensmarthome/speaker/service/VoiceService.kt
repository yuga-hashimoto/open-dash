package com.opensmarthome.speaker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.opensmarthome.speaker.data.preferences.AppPreferences
import com.opensmarthome.speaker.data.preferences.PreferenceKeys
import com.opensmarthome.speaker.voice.pipeline.VoicePipeline
import com.opensmarthome.speaker.voice.wakeword.VoskModelDownloader
import com.opensmarthome.speaker.voice.wakeword.VoskWakeWordDetector
import com.opensmarthome.speaker.voice.wakeword.WakeWordConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var preferences: AppPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeWordDetector: VoskWakeWordDetector? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("VoiceService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = VoiceServiceNotification.create(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Handle actions
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                Timber.d("VoiceService: trigger listening from external source")
                scope.launch { voicePipeline.startListening() }
            }
            ACTION_STOP_LISTENING -> {
                voicePipeline.stopSpeaking()
            }
            else -> {
                scope.launch { initializeWakeWord() }
            }
        }

        Timber.d("VoiceService started in foreground")
        return START_STICKY
    }

    private suspend fun initializeWakeWord() {
        try {
            Class.forName("org.vosk.Model")
        } catch (e: ClassNotFoundException) {
            Timber.w("Vosk library not available, wake word disabled. Add vosk-android dependency to enable.")
            return
        }

        try {
            val downloader = VoskModelDownloader(this)
            if (!downloader.isModelDownloaded()) {
                Timber.d("Vosk model not found, downloading...")
                downloader.downloadModel()
            }

            if (downloader.isModelDownloaded()) {
                val savedWakeWord = preferences.observe(PreferenceKeys.WAKE_WORD).first() ?: "hey speaker"
                val config = WakeWordConfig(keyword = savedWakeWord)
                wakeWordDetector = VoskWakeWordDetector(config, downloader.getModelDir())
                wakeWordDetector?.start {
                    Timber.d("Wake word detected! Starting voice pipeline...")
                    scope.launch {
                        voicePipeline.startListening()
                    }
                }
                Timber.d("Wake word detection active")
            } else {
                Timber.w("Vosk model unavailable, wake word disabled")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize wake word, continuing without it")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeWordDetector?.stop()
        voicePipeline.stopSpeaking()
        voicePipeline.destroy()
        super.onDestroy()
        Timber.d("VoiceService destroyed")
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_LISTENING = "com.opensmarthome.speaker.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.opensmarthome.speaker.STOP_LISTENING"

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }

        fun triggerListening(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            context.startService(intent)
        }
    }
}
