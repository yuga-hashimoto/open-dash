package com.opendash.app.voice.alarm

import android.content.Context
import com.opendash.app.tool.system.AlarmPlayer
import com.opendash.app.tool.system.AlarmPlayerFactory
import com.opendash.app.tool.system.AndroidMediaPlayerAlarmPlayer
import com.opendash.app.tool.system.MainLooperSafetyScheduler
import com.opendash.app.tool.system.SafetyScheduler
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the looping, volume-fading alarm sound for in-app recurring
 * alarms ([com.opendash.app.tool.alarm.AlarmToolExecutor]) — distinct
 * from [com.opendash.app.tool.system.AndroidTimerManager]'s own
 * per-timer alarm players, since alarms and timers have independent
 * lifecycles.
 *
 * A single Hilt singleton so a spoken "cancel"/"snooze" (handled by
 * `AlarmToolExecutor` in the live process) can silence a sound that
 * [AlarmFireReceiver] started by waking [com.opendash.app.service.VoiceService]
 * — same reasoning as `TimerManager`/`NotificationProvider` being
 * promoted to Hilt singletons (see P8.4 in docs/roadmap.md). The
 * looping [AlarmPlayer] only survives inside a live process, which is
 * why the receiver wakes the foreground service rather than trying to
 * start playback from inside its own `onReceive()` — Android freezes
 * that process shortly after the method returns.
 *
 * Volume ramps from [FADE_STEPS] first to last over
 * [FADE_STEP_INTERVAL_MS]-spaced ticks (~10s total) instead of blasting
 * at full volume immediately, then auto-silences after [MAX_RINGING_MS]
 * if nobody responds so a missed alarm doesn't ring — and drain the
 * battery — forever.
 */
class AlarmRingtoneController(
    private val alarmPlayerFactory: AlarmPlayerFactory,
    private val safetyScheduler: SafetyScheduler
) {

    /** Production constructor: MediaPlayer-based alarm + main-looper scheduler, same as [com.opendash.app.tool.system.AndroidTimerManager]. */
    constructor(context: Context) : this(
        alarmPlayerFactory = AlarmPlayerFactory { AndroidMediaPlayerAlarmPlayer(context) },
        safetyScheduler = MainLooperSafetyScheduler()
    )

    private val ringing = ConcurrentHashMap<String, RingingAlarm>()

    /**
     * Fired whenever the set of currently-ringing alarm ids changes
     * (start, stop, safety-cap auto-stop). Used by the wake-word-free
     * alert session (P21.5) so listening begins only while something is
     * actually ringing.
     */
    @Volatile
    var onRingingChanged: ((Boolean) -> Unit)? = null

    private data class RingingAlarm(val player: AlarmPlayer, val cancelSafety: () -> Unit)

    fun ringingIds(): Set<String> = ringing.keys.toSet()

    fun isRinging(): Boolean = ringing.isNotEmpty()

    fun startRinging(id: String) {
        if (ringing.containsKey(id)) return

        val player = runCatching { alarmPlayerFactory.create() }
            .onFailure { Timber.e(it, "AlarmPlayer factory threw for alarm $id") }
            .getOrNull() ?: return

        runCatching {
            player.setVolume(FADE_STEPS.first())
            player.startLooping()
        }.onFailure { Timber.e(it, "Failed to start ringing for alarm $id") }

        val cancelSafety = safetyScheduler.schedule(MAX_RINGING_MS) {
            Timber.w("Alarm $id hit ringing safety cap; auto-stopping")
            stopRinging(id)
        }
        ringing[id] = RingingAlarm(player, cancelSafety)

        scheduleFadeStep(id, player, nextStepIndex = 1)
        notifyRingingChanged()
    }

    fun stopRinging(id: String): Boolean {
        val existing = ringing.remove(id) ?: return false
        runCatching { existing.player.stop() }
            .onFailure { Timber.w(it, "Failed to stop ringing for alarm $id") }
        runCatching { existing.cancelSafety() }
        notifyRingingChanged()
        return true
    }

    fun stopAll() {
        ringing.keys.toList().forEach { stopRinging(it) }
    }

    private fun notifyRingingChanged() {
        runCatching { onRingingChanged?.invoke(isRinging()) }
            .onFailure { Timber.w(it, "onRingingChanged listener threw") }
    }

    private fun scheduleFadeStep(id: String, player: AlarmPlayer, nextStepIndex: Int) {
        if (nextStepIndex >= FADE_STEPS.size) return
        safetyScheduler.schedule(FADE_STEP_INTERVAL_MS) {
            // The alarm may have been stopped/snoozed since this tick was
            // scheduled — don't resurrect a volume change on a dead player.
            if (!ringing.containsKey(id)) return@schedule
            runCatching { player.setVolume(FADE_STEPS[nextStepIndex]) }
            scheduleFadeStep(id, player, nextStepIndex + 1)
        }
    }

    companion object {
        /** Max time a firing alarm can ring before auto-silencing (5 minutes, same cap as timers). */
        const val MAX_RINGING_MS: Long = 5L * 60L * 1000L
        const val FADE_STEP_INTERVAL_MS: Long = 2_000L
        val FADE_STEPS = listOf(0.15f, 0.3f, 0.5f, 0.7f, 1.0f)
    }
}
