package com.opendash.app.voice.alarm

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure helper for computing the next epoch-millis trigger time for a
 * recurring (or one-shot) in-app alarm. Kept separate from
 * [com.opendash.app.tool.alarm.AlarmToolExecutor] and
 * [AlarmFireReceiver] for easy unit testing with a fixed "now" injected.
 *
 * [repeatDays] empty means one-shot: next occurrence of hour:minute,
 * rolling to tomorrow if that time has already passed today (matching
 * classic alarm-clock semantics, same convention as
 * [com.opendash.app.voice.fastpath.AlarmTimeCalculator]). Non-empty
 * means recurring: the nearest matching day at or after today, rolling
 * to next week if every matching day this week has already passed.
 */
object AlarmOccurrenceCalculator {

    fun nextTriggerMillis(
        now: LocalDateTime,
        hour: Int,
        minute: Int,
        repeatDays: Set<DayOfWeek>
    ): Long {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        val targetTime = LocalTime.of(hour, minute)
        val zone = ZoneId.systemDefault()

        if (repeatDays.isEmpty()) {
            var candidate = now.toLocalDate().atTime(targetTime)
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
            return candidate.atZone(zone).toInstant().toEpochMilli()
        }

        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek !in repeatDays) continue
            val candidate = date.atTime(targetTime)
            if (candidate.isAfter(now)) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        error("No matching day found in repeatDays within one week — repeatDays was non-empty so this should be unreachable")
    }

    /** Packs a [DayOfWeek] set into a 7-bit mask (bit 0 = Monday .. bit 6 = Sunday) for Room storage. */
    fun daysToMask(days: Set<DayOfWeek>): Int =
        days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    /** Inverse of [daysToMask]. */
    fun maskToDays(mask: Int): Set<DayOfWeek> =
        DayOfWeek.values().filter { (mask shr (it.value - 1)) and 1 == 1 }.toSet()
}
