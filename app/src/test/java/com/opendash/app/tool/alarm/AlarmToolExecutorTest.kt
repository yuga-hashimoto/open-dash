package com.opendash.app.tool.alarm

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.AlarmDao
import com.opendash.app.data.db.AlarmEntity
import com.opendash.app.tool.ToolCall
import com.opendash.app.voice.alarm.AlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AlarmToolExecutorTest {

    private lateinit var executor: AlarmToolExecutor
    private lateinit var dao: AlarmDao
    private lateinit var scheduler: AlarmScheduler
    private val fixedNow = LocalDateTime.of(2024, 1, 1, 8, 0) // Monday 08:00

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        executor = AlarmToolExecutor(dao, scheduler, nowProvider = { fixedNow })
    }

    @Test
    fun `availableTools exposes all alarm tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("set_recurring_alarm", "list_alarms", "cancel_alarm", "snooze_alarm")
    }

    @Test
    fun `set_recurring_alarm one-shot persists and schedules`() = runTest {
        val result = executor.execute(
            ToolCall("1", "set_recurring_alarm", mapOf("hour" to 9.0, "minute" to 0.0, "label" to "wake up"))
        )

        assertThat(result.success).isTrue()
        coVerify {
            dao.upsert(match { it.hour == 9 && it.minute == 0 && it.repeatDaysMask == 0 && it.label == "wake up" })
        }
        verify { scheduler.schedule(any(), "wake up", 9, 0, 0, any()) }
    }

    @Test
    fun `set_recurring_alarm with repeat_days persists mask`() = runTest {
        val result = executor.execute(
            ToolCall("2", "set_recurring_alarm", mapOf("hour" to 7.0, "minute" to 30.0, "repeat_days" to "mon,wed,fri"))
        )

        assertThat(result.success).isTrue()
        // mon=bit0(1) wed=bit2(4) fri=bit4(16) -> 21
        coVerify { dao.upsert(match { it.repeatDaysMask == 21 }) }
    }

    @Test
    fun `set_recurring_alarm missing hour returns error`() = runTest {
        val result = executor.execute(
            ToolCall("3", "set_recurring_alarm", mapOf("minute" to 0.0))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_recurring_alarm out of range hour returns error`() = runTest {
        val result = executor.execute(
            ToolCall("4", "set_recurring_alarm", mapOf("hour" to 24.0, "minute" to 0.0))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_recurring_alarm invalid repeat_days token returns error`() = runTest {
        val result = executor.execute(
            ToolCall("5", "set_recurring_alarm", mapOf("hour" to 9.0, "minute" to 0.0, "repeat_days" to "someday"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `list_alarms returns all alarms`() = runTest {
        coEvery { dao.listAll() } returns listOf(
            AlarmEntity("id-1", 7, 0, 0, "wake up", true)
        )

        val result = executor.execute(ToolCall("6", "list_alarms", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"label\":\"wake up\"")
    }

    @Test
    fun `list_alarms empty returns empty array`() = runTest {
        coEvery { dao.listAll() } returns emptyList()

        val result = executor.execute(ToolCall("7", "list_alarms", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("[]")
    }

    @Test
    fun `cancel_alarm deletes and unschedules`() = runTest {
        coEvery { dao.get("id-1") } returns AlarmEntity("id-1", 7, 0, 0, "wake up", true)

        val result = executor.execute(ToolCall("8", "cancel_alarm", mapOf("id" to "id-1")))

        assertThat(result.success).isTrue()
        coVerify { dao.delete("id-1") }
        verify { scheduler.cancel("id-1") }
    }

    @Test
    fun `cancel_alarm not found returns error`() = runTest {
        coEvery { dao.get("nope") } returns null

        val result = executor.execute(ToolCall("9", "cancel_alarm", mapOf("id" to "nope")))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `snooze_alarm reschedules with default minutes`() = runTest {
        coEvery { dao.get("id-1") } returns AlarmEntity("id-1", 7, 0, 0, "wake up", true)

        val result = executor.execute(ToolCall("10", "snooze_alarm", mapOf("id" to "id-1")))

        assertThat(result.success).isTrue()
        verify { scheduler.schedule("id-1", "wake up", 7, 0, 0, any()) }
    }

    @Test
    fun `snooze_alarm not found returns error`() = runTest {
        coEvery { dao.get("nope") } returns null

        val result = executor.execute(ToolCall("11", "snooze_alarm", mapOf("id" to "nope")))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("12", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
