package com.opendash.app.voice.alarm

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.system.AlarmPlayer
import com.opendash.app.tool.system.AlarmPlayerFactory
import com.opendash.app.tool.system.SafetyScheduler
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [AlarmRingtoneController]'s ringing/fade/safety-cap
 * lifecycle. Mirrors the fake-player + manually-driven-scheduler pattern
 * from `AndroidTimerManagerTest` so the two firing-alarm implementations
 * (timer vs. in-app alarm) stay behaviorally consistent.
 */
class AlarmRingtoneControllerTest {

    private class FakeAlarmPlayer : AlarmPlayer {
        var started = 0
        var stopped = 0
        val volumeHistory = mutableListOf<Float>()
        override fun startLooping() { started += 1 }
        override fun stop() { stopped += 1 }
        override fun setVolume(volume: Float) { volumeHistory.add(volume) }
    }

    private class RecordingScheduler : SafetyScheduler {
        data class Pending(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val pending = mutableListOf<Pending>()
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = Pending(delayMs, action)
            pending.add(entry)
            return { entry.cancelled = true }
        }
        /** Runs every not-yet-cancelled pending callback that was scheduled so far, in order, once each. */
        fun runAllPendingOnce() {
            pending.filterNot { it.cancelled }.forEach { it.action() }
        }
    }

    private fun controller(
        player: FakeAlarmPlayer = FakeAlarmPlayer(),
        scheduler: RecordingScheduler = RecordingScheduler()
    ): Triple<AlarmRingtoneController, FakeAlarmPlayer, RecordingScheduler> {
        val ctrl = AlarmRingtoneController(
            alarmPlayerFactory = AlarmPlayerFactory { player },
            safetyScheduler = scheduler
        )
        return Triple(ctrl, player, scheduler)
    }

    @Test
    fun `startRinging starts the player at the initial fade volume`() {
        val (ctrl, player, _) = controller()

        ctrl.startRinging("a1")

        assertThat(player.started).isEqualTo(1)
        assertThat(player.volumeHistory.first()).isEqualTo(AlarmRingtoneController.FADE_STEPS.first())
    }

    @Test
    fun `startRinging twice for the same id does not double-start`() {
        val (ctrl, player, _) = controller()

        ctrl.startRinging("a1")
        ctrl.startRinging("a1")

        assertThat(player.started).isEqualTo(1)
    }

    @Test
    fun `stopRinging stops the player and cancels the safety timer`() {
        val (ctrl, player, scheduler) = controller()
        ctrl.startRinging("a1")

        val stopped = ctrl.stopRinging("a1")

        assertThat(stopped).isTrue()
        assertThat(player.stopped).isEqualTo(1)
        assertThat(scheduler.pending.first().cancelled).isTrue()
    }

    @Test
    fun `stopRinging on unknown id returns false and touches nothing`() {
        val (ctrl, player, _) = controller()

        assertThat(ctrl.stopRinging("missing")).isFalse()
        assertThat(player.stopped).isEqualTo(0)
    }

    @Test
    fun `fade steps ramp the volume up over successive scheduled callbacks`() {
        val (ctrl, player, scheduler) = controller()

        ctrl.startRinging("a1")
        // startRinging already applies FADE_STEPS[0] synchronously; each
        // scheduled tick applies the next step and schedules the one
        // after, so draining (size - 1) ticks walks through the rest.
        repeat(AlarmRingtoneController.FADE_STEPS.size - 1) {
            scheduler.pending.lastOrNull { !it.cancelled }?.action?.invoke()
        }

        assertThat(player.volumeHistory).isEqualTo(AlarmRingtoneController.FADE_STEPS)
    }

    @Test
    fun `fade does not continue after the alarm has been stopped`() {
        val (ctrl, player, scheduler) = controller()
        ctrl.startRinging("a1")
        val volumeCountBeforeStop = player.volumeHistory.size

        ctrl.stopRinging("a1")
        // Any fade tick that was already scheduled (not the safety-cap
        // one, which was just cancelled) firing late must be a no-op.
        scheduler.pending.filterNot { it.cancelled }.forEach { it.action() }

        assertThat(player.volumeHistory).hasSize(volumeCountBeforeStop)
    }

    @Test
    fun `safety cap auto-stops ringing after the max duration`() {
        val (ctrl, player, scheduler) = controller()
        ctrl.startRinging("a1")

        val safetyEntry = scheduler.pending.first { it.delayMs == AlarmRingtoneController.MAX_RINGING_MS }
        safetyEntry.action()

        assertThat(player.stopped).isEqualTo(1)
    }

    @Test
    fun `stopAll stops every currently ringing alarm`() {
        val player1 = FakeAlarmPlayer()
        val player2 = FakeAlarmPlayer()
        val players = ArrayDeque(listOf<AlarmPlayer>(player1, player2))
        val scheduler = RecordingScheduler()
        val ctrl = AlarmRingtoneController(
            alarmPlayerFactory = AlarmPlayerFactory { players.removeFirst() },
            safetyScheduler = scheduler
        )

        ctrl.startRinging("a1")
        ctrl.startRinging("a2")
        ctrl.stopAll()

        assertThat(player1.stopped).isEqualTo(1)
        assertThat(player2.stopped).isEqualTo(1)
    }

    @Test
    fun `ringingIds and isRinging track live alarms`() {
        val (ctrl, _, _) = controller()
        assertThat(ctrl.isRinging()).isFalse()
        assertThat(ctrl.ringingIds()).isEmpty()

        ctrl.startRinging("a1")
        assertThat(ctrl.isRinging()).isTrue()
        assertThat(ctrl.ringingIds()).containsExactly("a1")

        ctrl.stopRinging("a1")
        assertThat(ctrl.isRinging()).isFalse()
        assertThat(ctrl.ringingIds()).isEmpty()
    }

    @Test
    fun `onRingingChanged fires on start and stop`() {
        val (ctrl, _, _) = controller()
        val events = mutableListOf<Boolean>()
        ctrl.onRingingChanged = { events += it }

        ctrl.startRinging("a1")
        ctrl.stopRinging("a1")

        assertThat(events).containsExactly(true, false).inOrder()
    }
}
