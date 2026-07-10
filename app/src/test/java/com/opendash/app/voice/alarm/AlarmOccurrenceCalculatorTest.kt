package com.opendash.app.voice.alarm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmOccurrenceCalculatorTest {

    private val zone = ZoneId.systemDefault()

    @Test
    fun `one-shot alarm later today fires today`() {
        // Monday 2024-01-01 08:00
        val now = LocalDateTime.of(2024, 1, 1, 8, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(now, hour = 9, minute = 0, repeatDays = emptySet())

        val expected = LocalDateTime.of(2024, 1, 1, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `one-shot alarm earlier today rolls to tomorrow`() {
        val now = LocalDateTime.of(2024, 1, 1, 10, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(now, hour = 9, minute = 0, repeatDays = emptySet())

        val expected = LocalDateTime.of(2024, 1, 2, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `one-shot alarm at exact current time rolls to tomorrow`() {
        val now = LocalDateTime.of(2024, 1, 1, 9, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(now, hour = 9, minute = 0, repeatDays = emptySet())

        val expected = LocalDateTime.of(2024, 1, 2, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `repeating alarm fires today if today matches and time hasn't passed`() {
        // Monday 2024-01-01 08:00, repeat on Monday/Wednesday/Friday
        val now = LocalDateTime.of(2024, 1, 1, 8, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(
            now, hour = 9, minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )

        val expected = LocalDateTime.of(2024, 1, 1, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `repeating alarm skips to next matching day if today's time has passed`() {
        // Monday 2024-01-01 10:00 (past 9am), repeat Mon/Wed/Fri -> next is Wednesday
        val now = LocalDateTime.of(2024, 1, 1, 10, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(
            now, hour = 9, minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )

        val expected = LocalDateTime.of(2024, 1, 3, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `repeating alarm wraps to next week when no more matching days remain`() {
        // Friday 2024-01-05 10:00 (past 9am), repeat Mon/Wed/Fri -> next is following Monday
        val now = LocalDateTime.of(2024, 1, 5, 10, 0)
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(
            now, hour = 9, minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )

        val expected = LocalDateTime.of(2024, 1, 8, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `single repeat day next week when today is the only day and time passed`() {
        val now = LocalDateTime.of(2024, 1, 1, 10, 0) // Monday, past 9am
        val result = AlarmOccurrenceCalculator.nextTriggerMillis(
            now, hour = 9, minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY)
        )

        val expected = LocalDateTime.of(2024, 1, 8, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `dayOfWeekSetToMask and back round-trips`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)
        val mask = AlarmOccurrenceCalculator.daysToMask(days)
        assertThat(AlarmOccurrenceCalculator.maskToDays(mask)).isEqualTo(days)
    }

    @Test
    fun `empty day set masks to zero`() {
        assertThat(AlarmOccurrenceCalculator.daysToMask(emptySet())).isEqualTo(0)
        assertThat(AlarmOccurrenceCalculator.maskToDays(0)).isEmpty()
    }
}
