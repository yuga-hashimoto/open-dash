package com.opendash.app.voice.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VoiceMeasurementRecorderTest {

    @Test
    fun `latency samples aggregate average p50 p95 max`() {
        var now = 1_000L
        val recorder = VoiceMeasurementRecorder(
            clockMs = { now },
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        listOf(100L, 200L, 300L, 400L, 500L).forEach { duration ->
            recorder.record(
                VoiceMeasurementRecorder.EventType.LATENCY,
                name = "WAKE_TO_LISTENING",
                durationMs = duration,
            )
            now += 1
        }

        val summary = recorder.snapshot().latency["WAKE_TO_LISTENING"]
        assertThat(summary).isNotNull()
        assertThat(summary!!.count).isEqualTo(5)
        assertThat(summary.averageMs).isEqualTo(300L)
        assertThat(summary.maxMs).isEqualTo(500L)
        assertThat(summary.p50Ms).isAtLeast(200L)
        assertThat(summary.p95Ms).isAtLeast(400L)
    }

    @Test
    fun `wake and false-wake counters increment independently`() {
        val recorder = VoiceMeasurementRecorder(
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        recorder.recordWakeDetected()
        recorder.recordWakeDetected()
        recorder.recordFalseWake()
        recorder.recordMissedWake()

        val snap = recorder.snapshot()
        assertThat(snap.wakeCount).isEqualTo(2)
        assertThat(snap.falseWakeCount).isEqualTo(1)
        assertThat(snap.missedWakeCount).isEqualTo(1)
    }

    @Test
    fun `battery and thermal samples are retained from injected readers`() {
        var battery = 88
        var thermal = "NORMAL"
        val recorder = VoiceMeasurementRecorder(
            batteryPercentReader = { battery },
            thermalStatusReader = { thermal },
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        recorder.recordBatterySample()
        battery = 75
        thermal = "WARM"
        recorder.recordThermalSample()
        recorder.recordBatterySample(percent = 60)
        recorder.recordThermalSample(status = "HOT")

        val snap = recorder.snapshot()
        assertThat(snap.batterySamples).containsExactly(88, 60).inOrder()
        assertThat(snap.thermalSamples).containsExactly("WARM", "HOT").inOrder()
    }

    @Test
    fun `hard sample cap drops oldest samples while counters remain session totals`() {
        val recorder = VoiceMeasurementRecorder(
            maxSamples = 3,
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        repeat(5) { recorder.recordWakeDetected() }

        val snap = recorder.snapshot()
        assertThat(snap.sampleCount).isEqualTo(3)
        assertThat(snap.samples).hasSize(3)
        assertThat(snap.wakeCount).isEqualTo(5)
    }

    @Test
    fun `export redacts transcript and api-key-like strings`() {
        val recorder = VoiceMeasurementRecorder(
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        recorder.record(
            VoiceMeasurementRecorder.EventType.NOTE,
            name = "user said set a five minute timer please now",
        )
        recorder.record(
            VoiceMeasurementRecorder.EventType.NOTE,
            name = "sk-proj-abcdefghijklmnopqrstuvwxyz012345",
        )
        recorder.record(
            VoiceMeasurementRecorder.EventType.ALERT_OUTCOME,
            name = "Handled",
        )

        val json = recorder.exportJson()
        val text = recorder.exportText()

        assertThat(json).doesNotContain("set a five minute timer")
        assertThat(json).doesNotContain("sk-proj-abcdefghijklmnopqrstuvwxyz012345")
        assertThat(json).contains(VoiceMeasurementRecorder.REDACTED)
        assertThat(json).contains("Handled")

        assertThat(text).doesNotContain("set a five minute timer")
        assertThat(text).doesNotContain("sk-proj-abcdefghijklmnopqrstuvwxyz012345")
        assertThat(text).contains(VoiceMeasurementRecorder.REDACTED)
        assertThat(text).contains("Handled")
    }

    @Test
    fun `export is deterministic for the same recorded session`() {
        var now = 10_000L
        val recorder = VoiceMeasurementRecorder(
            clockMs = { now },
            deviceModel = "PixelTablet",
            androidRelease = "15",
            appVersion = "1.2.3",
        )

        recorder.recordWakeDetected()
        now = 10_100L
        recorder.record(
            VoiceMeasurementRecorder.EventType.LATENCY,
            name = "STT_DURATION",
            durationMs = 250L,
        )
        now = 10_200L
        recorder.recordAlertOutcome("Handled")

        assertThat(recorder.exportJson()).isEqualTo(recorder.exportJson())
        assertThat(recorder.exportText()).isEqualTo(recorder.exportText())
    }

    @Test
    fun `clear resets samples and counters`() {
        val recorder = VoiceMeasurementRecorder(
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )
        recorder.recordWakeDetected()
        recorder.recordFalseWake()
        recorder.recordBatterySample(percent = 50)
        recorder.clear()

        val snap = recorder.snapshot()
        assertThat(snap.sampleCount).isEqualTo(0)
        assertThat(snap.wakeCount).isEqualTo(0)
        assertThat(snap.falseWakeCount).isEqualTo(0)
        assertThat(snap.batterySamples).isEmpty()
        assertThat(snap.latency).isEmpty()
    }

    @Test
    fun `lifecycle convenience methods record typed counters`() {
        val recorder = VoiceMeasurementRecorder(
            deviceModel = "TestTablet",
            androidRelease = "14",
            appVersion = "0.0-test",
        )

        recorder.recordSttStart()
        recorder.recordSttFinal(durationMs = 400)
        recorder.recordSttError()
        recorder.recordTtsStart()
        recorder.recordTtsStop(durationMs = 120)
        recorder.recordTurnStart()
        recorder.recordTurnEnd(durationMs = 900)
        recorder.recordState("Listening")

        val snap = recorder.snapshot()
        assertThat(snap.sttStartCount).isEqualTo(1)
        assertThat(snap.sttFinalCount).isEqualTo(1)
        assertThat(snap.sttErrorCount).isEqualTo(1)
        assertThat(snap.ttsStartCount).isEqualTo(1)
        assertThat(snap.ttsStopCount).isEqualTo(1)
        assertThat(snap.turnStartCount).isEqualTo(1)
        assertThat(snap.turnEndCount).isEqualTo(1)
        assertThat(snap.samples.map { it.type }).contains(
            VoiceMeasurementRecorder.EventType.STATE,
        )
    }
}
