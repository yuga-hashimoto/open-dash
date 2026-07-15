package com.opendash.app.voice.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe, bounded in-memory recorder for voice/power measurement runs.
 *
 * Stores only numeric counters, state names, timestamps, battery percent,
 * thermal status, and explicit device/build identifiers. Never stores
 * transcripts, API keys, or secrets — free-form labels are sanitized on
 * record and again on export.
 *
 * This is measurement instrumentation, not a claim of hardware validation.
 * Battery percent is observed as-is; wattage is never invented.
 */
class VoiceMeasurementRecorder(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val batteryPercentReader: () -> Int? = { null },
    private val thermalStatusReader: () -> String? = { null },
    private val deviceModel: String = "unknown",
    private val androidRelease: String = "unknown",
    private val appVersion: String = "unknown",
) {
    enum class EventType {
        WAKE_DETECTED,
        FALSE_WAKE,
        MISSED_WAKE,
        STT_START,
        STT_FINAL,
        STT_ERROR,
        TTS_START,
        TTS_STOP,
        TURN_START,
        TURN_END,
        STATE,
        LATENCY,
        BATTERY,
        THERMAL,
        ALERT_OUTCOME,
        NOTE,
    }

    data class Sample(
        val type: EventType,
        val timestampMs: Long,
        val name: String? = null,
        val durationMs: Long? = null,
        val batteryPercent: Int? = null,
        val thermalStatus: String? = null,
    )

    data class LatencySummary(
        val count: Int,
        val averageMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
    )

    data class Snapshot(
        val deviceModel: String,
        val androidRelease: String,
        val appVersion: String,
        val startedAtMs: Long?,
        val sampleCount: Int,
        val wakeCount: Int,
        val falseWakeCount: Int,
        val missedWakeCount: Int,
        val sttStartCount: Int,
        val sttFinalCount: Int,
        val sttErrorCount: Int,
        val ttsStartCount: Int,
        val ttsStopCount: Int,
        val turnStartCount: Int,
        val turnEndCount: Int,
        val alertOutcomeCounts: Map<String, Int>,
        val latency: Map<String, LatencySummary>,
        val batterySamples: List<Int>,
        val thermalSamples: List<String>,
        val samples: List<Sample>,
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private val startedAtMs = AtomicLong(0L)

    private val wakeCount = AtomicInteger(0)
    private val falseWakeCount = AtomicInteger(0)
    private val missedWakeCount = AtomicInteger(0)
    private val sttStartCount = AtomicInteger(0)
    private val sttFinalCount = AtomicInteger(0)
    private val sttErrorCount = AtomicInteger(0)
    private val ttsStartCount = AtomicInteger(0)
    private val ttsStopCount = AtomicInteger(0)
    private val turnStartCount = AtomicInteger(0)
    private val turnEndCount = AtomicInteger(0)
    private val alertOutcomeCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val latencySamples = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun record(
        type: EventType,
        name: String? = null,
        durationMs: Long? = null,
        batteryPercent: Int? = null,
        thermalStatus: String? = null,
    ) {
        val ts = clockMs()
        startedAtMs.compareAndSet(0L, ts)
        val safeName = sanitizeLabel(name)
        val safeThermal = sanitizeLabel(thermalStatus)
        val sample = Sample(
            type = type,
            timestampMs = ts,
            name = safeName,
            durationMs = durationMs,
            batteryPercent = batteryPercent,
            thermalStatus = safeThermal,
        )
        bumpCounter(type, safeName)
        if (type == EventType.LATENCY && safeName != null && durationMs != null) {
            appendLatency(safeName, durationMs)
        }
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > maxSamples) samples.removeFirst()
        }
    }

    fun recordWakeDetected() = record(EventType.WAKE_DETECTED)

    fun recordFalseWake() = record(EventType.FALSE_WAKE)

    fun recordMissedWake() = record(EventType.MISSED_WAKE)

    fun recordSttStart() = record(EventType.STT_START)

    fun recordSttFinal(durationMs: Long? = null) =
        record(EventType.STT_FINAL, durationMs = durationMs)

    fun recordSttError() = record(EventType.STT_ERROR)

    fun recordTtsStart() = record(EventType.TTS_START)

    fun recordTtsStop(durationMs: Long? = null) =
        record(EventType.TTS_STOP, durationMs = durationMs)

    fun recordTurnStart() = record(EventType.TURN_START)

    fun recordTurnEnd(durationMs: Long? = null) =
        record(EventType.TURN_END, durationMs = durationMs)

    fun recordState(state: String) = record(EventType.STATE, name = state)

    fun recordLatency(span: String, durationMs: Long) =
        record(EventType.LATENCY, name = span, durationMs = durationMs)

    fun recordBatterySample(percent: Int? = null) {
        val value = percent ?: batteryPercentReader() ?: return
        record(EventType.BATTERY, batteryPercent = value)
    }

    fun recordThermalSample(status: String? = null) {
        val value = status ?: thermalStatusReader() ?: return
        record(EventType.THERMAL, thermalStatus = value)
    }

    fun recordAlertOutcome(outcome: String) =
        record(EventType.ALERT_OUTCOME, name = outcome)

    fun snapshot(): Snapshot {
        val sampleList = synchronized(lock) { samples.toList() }
        val battery = sampleList.mapNotNull { it.batteryPercent }
        val thermal = sampleList.mapNotNull { it.thermalStatus }
        val started = startedAtMs.get().takeIf { it > 0L }
        val latency = latencySamples.entries
            .sortedBy { it.key }
            .associate { (name, deque) ->
                val values = synchronized(deque) { deque.toList() }
                name to summarizeLatency(values)
            }
        val alerts = alertOutcomeCounts.entries
            .sortedBy { it.key }
            .associate { it.key to it.value.get() }
        return Snapshot(
            deviceModel = deviceModel,
            androidRelease = androidRelease,
            appVersion = appVersion,
            startedAtMs = started,
            sampleCount = sampleList.size,
            wakeCount = wakeCount.get(),
            falseWakeCount = falseWakeCount.get(),
            missedWakeCount = missedWakeCount.get(),
            sttStartCount = sttStartCount.get(),
            sttFinalCount = sttFinalCount.get(),
            sttErrorCount = sttErrorCount.get(),
            ttsStartCount = ttsStartCount.get(),
            ttsStopCount = ttsStopCount.get(),
            turnStartCount = turnStartCount.get(),
            turnEndCount = turnEndCount.get(),
            alertOutcomeCounts = alerts,
            latency = latency,
            batterySamples = battery,
            thermalSamples = thermal,
            samples = sampleList,
        )
    }

    fun clear() {
        synchronized(lock) { samples.clear() }
        startedAtMs.set(0L)
        wakeCount.set(0)
        falseWakeCount.set(0)
        missedWakeCount.set(0)
        sttStartCount.set(0)
        sttFinalCount.set(0)
        sttErrorCount.set(0)
        ttsStartCount.set(0)
        ttsStopCount.set(0)
        turnStartCount.set(0)
        turnEndCount.set(0)
        alertOutcomeCounts.clear()
        latencySamples.clear()
    }

    fun exportJson(): String {
        val snap = snapshot()
        val sb = StringBuilder()
        sb.append('{')
        appendJsonString(sb, "schema", SCHEMA_VERSION)
        sb.append(',')
        appendJsonString(sb, "deviceModel", snap.deviceModel)
        sb.append(',')
        appendJsonString(sb, "androidRelease", snap.androidRelease)
        sb.append(',')
        appendJsonString(sb, "appVersion", snap.appVersion)
        sb.append(',')
        appendJsonNumber(sb, "startedAtMs", snap.startedAtMs)
        sb.append(',')
        appendJsonNumber(sb, "sampleCount", snap.sampleCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "wakeCount", snap.wakeCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "falseWakeCount", snap.falseWakeCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "missedWakeCount", snap.missedWakeCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "sttStartCount", snap.sttStartCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "sttFinalCount", snap.sttFinalCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "sttErrorCount", snap.sttErrorCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "ttsStartCount", snap.ttsStartCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "ttsStopCount", snap.ttsStopCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "turnStartCount", snap.turnStartCount.toLong())
        sb.append(',')
        appendJsonNumber(sb, "turnEndCount", snap.turnEndCount.toLong())
        sb.append(',')
        sb.append("\"alertOutcomeCounts\":{")
        snap.alertOutcomeCounts.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            appendJsonStringKey(sb, key)
            sb.append(':').append(value)
        }
        sb.append("},")
        sb.append("\"latency\":{")
        snap.latency.entries.forEachIndexed { index, (key, summary) ->
            if (index > 0) sb.append(',')
            appendJsonStringKey(sb, key)
            sb.append(":{")
            sb.append("\"count\":").append(summary.count).append(',')
            sb.append("\"averageMs\":").append(summary.averageMs).append(',')
            sb.append("\"p50Ms\":").append(summary.p50Ms).append(',')
            sb.append("\"p95Ms\":").append(summary.p95Ms).append(',')
            sb.append("\"maxMs\":").append(summary.maxMs)
            sb.append('}')
        }
        sb.append("},")
        sb.append("\"batterySamples\":[")
        snap.batterySamples.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            sb.append(value)
        }
        sb.append("],")
        sb.append("\"thermalSamples\":[")
        snap.thermalSamples.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            appendJsonStringValue(sb, value)
        }
        sb.append("],")
        sb.append("\"samples\":[")
        snap.samples.forEachIndexed { index, sample ->
            if (index > 0) sb.append(',')
            sb.append('{')
            appendJsonString(sb, "type", sample.type.name)
            sb.append(',')
            appendJsonNumber(sb, "timestampMs", sample.timestampMs)
            if (sample.name != null) {
                sb.append(',')
                appendJsonString(sb, "name", sample.name)
            }
            if (sample.durationMs != null) {
                sb.append(',')
                appendJsonNumber(sb, "durationMs", sample.durationMs)
            }
            if (sample.batteryPercent != null) {
                sb.append(',')
                appendJsonNumber(sb, "batteryPercent", sample.batteryPercent.toLong())
            }
            if (sample.thermalStatus != null) {
                sb.append(',')
                appendJsonString(sb, "thermalStatus", sample.thermalStatus)
            }
            sb.append('}')
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    fun exportText(): String {
        val snap = snapshot()
        val lines = mutableListOf<String>()
        lines += "schema=$SCHEMA_VERSION"
        lines += "deviceModel=${snap.deviceModel}"
        lines += "androidRelease=${snap.androidRelease}"
        lines += "appVersion=${snap.appVersion}"
        lines += "startedAtMs=${snap.startedAtMs ?: ""}"
        lines += "sampleCount=${snap.sampleCount}"
        lines += "wakeCount=${snap.wakeCount}"
        lines += "falseWakeCount=${snap.falseWakeCount}"
        lines += "missedWakeCount=${snap.missedWakeCount}"
        lines += "sttStartCount=${snap.sttStartCount}"
        lines += "sttFinalCount=${snap.sttFinalCount}"
        lines += "sttErrorCount=${snap.sttErrorCount}"
        lines += "ttsStartCount=${snap.ttsStartCount}"
        lines += "ttsStopCount=${snap.ttsStopCount}"
        lines += "turnStartCount=${snap.turnStartCount}"
        lines += "turnEndCount=${snap.turnEndCount}"
        snap.alertOutcomeCounts.forEach { (name, count) ->
            lines += "alertOutcome.$name=$count"
        }
        snap.latency.forEach { (name, summary) ->
            lines += "latency.$name.count=${summary.count}"
            lines += "latency.$name.averageMs=${summary.averageMs}"
            lines += "latency.$name.p50Ms=${summary.p50Ms}"
            lines += "latency.$name.p95Ms=${summary.p95Ms}"
            lines += "latency.$name.maxMs=${summary.maxMs}"
        }
        lines += "batterySamples=${snap.batterySamples.joinToString(",")}"
        lines += "thermalSamples=${snap.thermalSamples.joinToString(",")}"
        snap.samples.forEachIndexed { index, sample ->
            val parts = mutableListOf(
                "type=${sample.type.name}",
                "ts=${sample.timestampMs}",
            )
            sample.name?.let { parts += "name=$it" }
            sample.durationMs?.let { parts += "durationMs=$it" }
            sample.batteryPercent?.let { parts += "battery=$it" }
            sample.thermalStatus?.let { parts += "thermal=$it" }
            lines += "sample[$index]=${parts.joinToString(" ")}"
        }
        return lines.joinToString("\n")
    }

    private fun bumpCounter(type: EventType, name: String?) {
        when (type) {
            EventType.WAKE_DETECTED -> wakeCount.incrementAndGet()
            EventType.FALSE_WAKE -> falseWakeCount.incrementAndGet()
            EventType.MISSED_WAKE -> missedWakeCount.incrementAndGet()
            EventType.STT_START -> sttStartCount.incrementAndGet()
            EventType.STT_FINAL -> sttFinalCount.incrementAndGet()
            EventType.STT_ERROR -> sttErrorCount.incrementAndGet()
            EventType.TTS_START -> ttsStartCount.incrementAndGet()
            EventType.TTS_STOP -> ttsStopCount.incrementAndGet()
            EventType.TURN_START -> turnStartCount.incrementAndGet()
            EventType.TURN_END -> turnEndCount.incrementAndGet()
            EventType.ALERT_OUTCOME -> {
                val key = name ?: "UNKNOWN"
                alertOutcomeCounts
                    .getOrPut(key) { AtomicInteger(0) }
                    .incrementAndGet()
            }
            EventType.STATE,
            EventType.LATENCY,
            EventType.BATTERY,
            EventType.THERMAL,
            EventType.NOTE -> Unit
        }
    }

    private fun appendLatency(name: String, durationMs: Long) {
        val deque = latencySamples.getOrPut(name) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(durationMs)
            while (deque.size > maxSamples) deque.removeFirst()
        }
    }

    private fun summarizeLatency(values: List<Long>): LatencySummary {
        if (values.isEmpty()) {
            return LatencySummary(count = 0, averageMs = 0, p50Ms = 0, p95Ms = 0, maxMs = 0)
        }
        val sorted = values.sorted()
        return LatencySummary(
            count = values.size,
            averageMs = values.average().toLong(),
            p50Ms = percentile(sorted, 50),
            p95Ms = percentile(sorted, 95),
            maxMs = sorted.last(),
        )
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = (sorted.size * p / 100).coerceAtMost(sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 256
        const val REDACTED = "[REDACTED]"
        const val SCHEMA_VERSION = "voice-measurement-v1"

        private val API_KEY_PATTERN = Regex(
            """(?i)(sk-[A-Za-z0-9_-]{10,}|sk-ant-[A-Za-z0-9_-]{10,}|sk-proj-[A-Za-z0-9_-]{10,}|""" +
                """(api[_-]?key|access[_-]?token|secret|password)\s*[:=]\s*\S+)"""
        )

        /**
         * Keep short machine labels (state names, outcome enums, span names).
         * Drop free-form transcripts and anything that looks like a secret.
         */
        fun sanitizeLabel(input: String?): String? {
            if (input == null) return null
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            if (API_KEY_PATTERN.containsMatchIn(trimmed)) return REDACTED
            if (trimmed.length > 64) return REDACTED
            if (trimmed.count { it == ' ' } >= 3) return REDACTED
            return trimmed
        }
    }

    private fun appendJsonString(sb: StringBuilder, key: String, value: String) {
        appendJsonStringKey(sb, key)
        sb.append(':')
        appendJsonStringValue(sb, value)
    }

    private fun appendJsonNumber(sb: StringBuilder, key: String, value: Long?) {
        appendJsonStringKey(sb, key)
        sb.append(':')
        if (value == null) sb.append("null") else sb.append(value)
    }

    private fun appendJsonStringKey(sb: StringBuilder, key: String) {
        appendJsonStringValue(sb, key)
    }

    private fun appendJsonStringValue(sb: StringBuilder, value: String) {
        sb.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
