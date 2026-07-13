package com.opendash.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.pipeline.VoicePipeline
import com.opendash.app.voice.wakeword.VoskModelDownloader
import com.opendash.app.voice.wakeword.VoskWakeWordDetector
import com.opendash.app.voice.wakeword.WakeWordConfig
import com.opendash.app.voice.wakeword.WakeWordDetector
import com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordDetector
import com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordModelDownloader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Always-on voice service managing wake word detection and voice pipeline.
 *
 * Reference: OpenClaw Assistant HotwordService.kt
 * Key pattern: Pause/Resume wake word via broadcast when STT session starts/ends.
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var saverStateProvider: com.opendash.app.util.SaverStateProvider
    @Inject lateinit var multicastDiscovery: com.opendash.app.util.MulticastDiscovery
    @Inject lateinit var announcementServer: com.opendash.app.multiroom.AnnouncementServer
    @Inject lateinit var peerLivenessTracker: com.opendash.app.multiroom.PeerLivenessTracker
    @Inject lateinit var toolExecutor: com.opendash.app.tool.ToolExecutor
    @Inject lateinit var alarmRingtoneController: com.opendash.app.voice.alarm.AlarmRingtoneController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val multiroomController = com.opendash.app.multiroom.MultiroomLifecycleController(
        onStart = { startMultiroom() },
        onStop = { stopMultiroom() },
    )
    private var wakeWordDetector: WakeWordDetector? = null
    @Volatile
    private var isSessionActive = false
    private var resumeJob: kotlinx.coroutines.Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Broadcast receiver for pause/resume hotword detection.
     * Reference: OpenClaw Assistant controlReceiver pattern.
     */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_HOTWORD -> {
                    Timber.d("Pause signal received — stopping wake word for STT")
                    isSessionActive = true
                    resumeJob?.cancel() // Cancel any pending resume
                    resumeJob = null
                    wakeWordDetector?.stop()
                    wakeWordDetector = null // OpenClaw: destroy + recreate
                    acquireWakeLock()
                }
                ACTION_RESUME_HOTWORD -> {
                    Timber.d("Resume signal received — restarting wake word")
                    isSessionActive = false
                    releaseWakeLock()
                    resumeJob?.cancel()
                    resumeJob = scope.launch {
                        delay(500) // Brief delay to ensure mic is fully released
                        if (!isSessionActive) startWakeWord()
                    }
                }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "OpenDash:VoiceSession"
                )
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire(5 * 60 * 1000L)
        } catch (e: Exception) {
            Timber.w(e, "WakeLock acquire failed")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Timber.w(e, "WakeLock release failed")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("VoiceService created")

        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE_HOTWORD)
            addAction(ACTION_RESUME_HOTWORD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // Opt-in multi-room: advertise via mDNS AND start listening on the
        // advertised port for NDJSON envelopes. The server only dispatches
        // a message after HMAC verification (P17.2), so a missing secret
        // means the port accepts connections but drops every envelope —
        // safe default for users who enabled broadcast before setting the
        // shared secret.
        //
        // Observe the preference for the service's lifetime so toggling the
        // Settings switch at runtime starts or tears down the subsystem
        // without requiring a service restart.
        scope.launch {
            preferences.observe(PreferenceKeys.MULTIROOM_BROADCAST_ENABLED)
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    multiroomController.setEnabled(enabled ?: false)
                }
        }
    }

    private suspend fun startMultiroom() {
        multicastDiscovery.register()
        multicastDiscovery.start()
        announcementServer.start()
        // Liveness tracker relies on the broadcaster fan-out + the
        // dispatcher's onHeartbeat callback. Start it after the server
        // is listening so the very first inbound heartbeat has a place
        // to land.
        peerLivenessTracker.start()
    }

    private fun stopMultiroom() {
        // Teardown mirrors startMultiroom in reverse. Each step is
        // best-effort — we do not want a stray ServerSocket close
        // failure to leave the mDNS registration stranded.
        runCatching { peerLivenessTracker.stop() }
        runCatching { announcementServer.stop() }
        runCatching { multicastDiscovery.unregister() }
        runCatching { multicastDiscovery.stop() }
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

        when (intent?.action) {
            ACTION_START_LISTENING -> {
                Timber.d("VoiceService: trigger listening")
                // Pause wake word and recreate (OpenClaw destroy+recreate)
                isSessionActive = true
                resumeJob?.cancel()
                resumeJob = null
                wakeWordDetector?.stop()
                wakeWordDetector = null
                acquireWakeLock()
                scope.launch {
                    delay(500) // Wait for mic release
                    voicePipeline.startListening()
                }
            }
            ACTION_STOP_LISTENING -> {
                voicePipeline.stopSpeaking()
            }
            ACTION_RUN_ROUTINE -> {
                val routineName = intent.getStringExtra(EXTRA_ROUTINE_NAME)
                scope.launch {
                    initializeWakeWord()
                    if (routineName != null) runScheduledRoutine(routineName)
                }
            }
            ACTION_ALARM_RINGING -> {
                val alarmId = intent.getStringExtra(EXTRA_ALARM_ID)
                if (alarmId != null) alarmRingtoneController.startRinging(alarmId)
                scope.launch { initializeWakeWord() }
            }
            else -> {
                scope.launch { initializeWakeWord() }
            }
        }

        Timber.d("VoiceService started in foreground")
        return START_STICKY
    }

    /**
     * Invoked when [RoutineFireReceiver][com.opendash.app.voice.routine.RoutineFireReceiver]
     * wakes the service to run a scheduled routine. Dispatches through
     * the same `run_routine` tool the LLM/voice path uses, so a
     * scheduled routine and a spoken "run my morning routine" behave
     * identically.
     */
    private suspend fun runScheduledRoutine(routineName: String) {
        try {
            val result = toolExecutor.execute(
                com.opendash.app.tool.ToolCall(
                    id = "scheduled_routine_${System.currentTimeMillis()}",
                    name = "run_routine",
                    arguments = mapOf("name" to routineName)
                )
            )
            Timber.d("Scheduled routine '$routineName' ran: success=${result.success}")
        } catch (e: Exception) {
            Timber.e(e, "Scheduled routine '$routineName' failed to run")
        }
    }

    private suspend fun initializeWakeWord() {
        // Check HOTWORD_ENABLED preference (default true)
        val hotwordEnabled = preferences.observe(PreferenceKeys.HOTWORD_ENABLED).first() ?: true
        if (!hotwordEnabled) {
            Timber.d("Hotword disabled via preference, skipping wake word init")
            return
        }

        try {
            Class.forName("org.vosk.Model")
        } catch (e: ClassNotFoundException) {
            Timber.w("Vosk library not available, wake word disabled.")
            return
        }

        try {
            val downloader = VoskModelDownloader(this)
            if (!downloader.isModelDownloaded()) {
                Timber.d("Vosk model not found, downloading...")
                downloader.downloadModel()
            }

            if (downloader.isModelDownloaded()) {
                startWakeWord()
            } else {
                Timber.w("Vosk model unavailable, wake word disabled")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize wake word")
        }
    }

    private suspend fun startWakeWord() {
        if (isSessionActive) return

        // Re-check HOTWORD_ENABLED at runtime (user may have toggled it off)
        val hotwordEnabled = preferences.observe(PreferenceKeys.HOTWORD_ENABLED).first() ?: true
        if (!hotwordEnabled) {
            Timber.d("Hotword disabled at runtime, not starting detector")
            return
        }

        // Battery + thermal saver: skip wake word when either the battery is
        // low (and the device is unplugged) or the chassis is reporting a
        // thermal throttle. Single source of truth = SaverStateProvider,
        // so the Ambient saver chip and this gate can never drift apart
        // (P14.8). Preference defaults off, so users who keep the tablet
        // plugged in continuously aren't affected.
        val saver = saverStateProvider.state.value
        if (saver.active) {
            Timber.d(
                "Battery/thermal saver active (reason=${saver.reason}, " +
                    "batteryLow=${saver.batteryLow}, " +
                    "thermalThrottling=${saver.thermalThrottling}), skipping wake word"
            )
            return
        }

        try {
            val savedWakeWord = preferences.observe(PreferenceKeys.WAKE_WORD).first() ?: "dash"
            val savedSensitivity = preferences.observe(PreferenceKeys.WAKE_WORD_SENSITIVITY).first() ?: 0.6f
            val config = WakeWordConfig(keyword = savedWakeWord, sensitivity = savedSensitivity)
            val engine = preferences.observe(PreferenceKeys.WAKE_WORD_ENGINE).first() ?: ENGINE_VOSK

            val detector = if (engine == ENGINE_OPEN_WAKE_WORD) {
                buildOpenWakeWordDetector() ?: buildVoskDetector(config)
            } else {
                buildVoskDetector(config)
            }
            if (detector == null) {
                Timber.w("No wake word model downloaded for engine=$engine, not starting detector")
                return
            }

            // Always create fresh detector (OpenClaw pattern: destroy + recreate)
            wakeWordDetector?.stop()
            wakeWordDetector = detector
            detector.start { onWakeWordDetected() }
            Timber.d("Wake word detection active for: '$savedWakeWord' (engine=$engine)")

            // openWakeWord's start() fails asynchronously (retries with backoff,
            // then gives up silently — see OpenWakeWordDetector.start()) rather
            // than throwing synchronously, so the try/catch around this block
            // can't see that failure. Fall back to Vosk if it never actually
            // started listening, so the device never ends up with no working
            // wake word at all just because openWakeWord's model was corrupt
            // or its ONNX session failed to load.
            if (engine == ENGINE_OPEN_WAKE_WORD && detector is OpenWakeWordDetector) {
                watchForOpenWakeWordGiveUp(detector, config)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start wake word detection")
        }
    }

    private fun buildVoskDetector(config: WakeWordConfig): WakeWordDetector? {
        val downloader = VoskModelDownloader(this)
        if (!downloader.isModelDownloaded()) return null
        return VoskWakeWordDetector(config, downloader.getModelDir())
    }

    private fun buildOpenWakeWordDetector(): WakeWordDetector? {
        val downloader = OpenWakeWordModelDownloader(this)
        if (!downloader.allDownloaded()) return null
        return OpenWakeWordDetector(
            threshold = OpenWakeWordDetector.DEFAULT_THRESHOLD,
            modelDir = downloader.modelDirectory()
        )
    }

    private fun onWakeWordDetected() {
        Timber.d("Wake word detected! Pausing hotword and starting STT...")
        isSessionActive = true
        wakeWordDetector?.stop()
        scope.launch {
            delay(300) // Wait for mic release
            voicePipeline.startListening()
        }
    }

    private fun watchForOpenWakeWordGiveUp(detector: OpenWakeWordDetector, config: WakeWordConfig) {
        scope.launch {
            delay(OPEN_WAKE_WORD_GIVE_UP_WATCH_MS)
            if (wakeWordDetector !== detector) return@launch // superseded by a newer start/stop
            if (isSessionActive) return@launch // it worked at least once already
            if (!detector.isListening.value) {
                Timber.w("openWakeWord failed to start after its retry budget; falling back to Vosk")
                val fallback = buildVoskDetector(config) ?: return@launch
                wakeWordDetector?.stop()
                wakeWordDetector = fallback
                fallback.start { onWakeWordDetected() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) { /* ignore */ }
        wakeWordDetector?.stop()
        voicePipeline.stopSpeaking()
        voicePipeline.destroy()
        runCatching { alarmRingtoneController.stopAll() }
        // Safe to call even if we never registered — controller is idempotent
        // and each teardown step is already wrapped in runCatching.
        stopMultiroom()
        super.onDestroy()
        Timber.d("VoiceService destroyed")
    }

    companion object {
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_OPEN_WAKE_WORD = "openwakeword"

        /**
         * Must exceed openWakeWord's own worst-case retry budget (5 attempts,
         * backoff 1000/2000/3000/4000/5000ms = 15s) with margin, so the
         * fallback watcher never fires while a legitimate retry is still
         * in flight.
         */
        const val OPEN_WAKE_WORD_GIVE_UP_WATCH_MS = 20_000L

        const val NOTIFICATION_ID = 1001
        const val ACTION_START_LISTENING = "com.opendash.app.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.opendash.app.STOP_LISTENING"
        const val ACTION_PAUSE_HOTWORD = "com.opendash.app.PAUSE_HOTWORD"
        const val ACTION_RESUME_HOTWORD = "com.opendash.app.RESUME_HOTWORD"
        const val ACTION_RUN_ROUTINE = "com.opendash.app.RUN_ROUTINE"
        const val ACTION_ALARM_RINGING = "com.opendash.app.ALARM_RINGING"
        const val EXTRA_ROUTINE_NAME = "routine_name"
        const val EXTRA_ALARM_ID = "alarm_id"

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Wakes (or reuses) the service and runs [routineName] via the run_routine tool. */
        fun startWithRoutine(context: Context, routineName: String) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_RUN_ROUTINE
                putExtra(EXTRA_ROUTINE_NAME, routineName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Wakes (or reuses) the service and starts the looping/fading alarm sound for [alarmId]. */
        fun startWithAlarmRinging(context: Context, alarmId: String) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_ALARM_RINGING
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
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

        fun pauseHotword(context: Context) {
            try {
                context.sendBroadcast(Intent(ACTION_PAUSE_HOTWORD).setPackage(context.packageName))
            } catch (e: Exception) {
                Timber.w(e, "pauseHotword broadcast failed")
            }
        }

        fun resumeHotword(context: Context) {
            try {
                context.sendBroadcast(Intent(ACTION_RESUME_HOTWORD).setPackage(context.packageName))
            } catch (e: Exception) {
                Timber.w(e, "resumeHotword broadcast failed")
            }
        }
    }
}
