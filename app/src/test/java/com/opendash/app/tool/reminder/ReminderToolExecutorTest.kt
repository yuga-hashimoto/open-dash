package com.opendash.app.tool.reminder

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.ReminderDao
import com.opendash.app.data.db.ReminderEntity
import com.opendash.app.tool.ToolCall
import com.opendash.app.voice.reminder.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReminderToolExecutorTest {

    private lateinit var executor: ReminderToolExecutor
    private lateinit var dao: ReminderDao
    private lateinit var scheduler: ReminderScheduler
    private val fixedNowMs = 1_700_000_000_000L // fixed "now" for deterministic tests

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        executor = ReminderToolExecutor(dao, scheduler, clock = { fixedNowMs })
    }

    @Test
    fun `availableTools exposes all reminder tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("set_reminder", "list_reminders", "cancel_reminder")
    }

    @Test
    fun `set_reminder persists and schedules a future reminder`() = runTest {
        val result = executor.execute(
            ToolCall("1", "set_reminder", mapOf("text" to "take out trash", "trigger_time" to "2100-01-01T09:00:00"))
        )

        assertThat(result.success).isTrue()
        coVerify {
            dao.upsert(match { it.text == "take out trash" })
        }
        verify { scheduler.schedule(any(), "take out trash", any()) }
    }

    @Test
    fun `set_reminder missing text returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2", "set_reminder", mapOf("trigger_time" to "2100-01-01T09:00:00"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_reminder blank text returns error`() = runTest {
        val result = executor.execute(
            ToolCall("2b", "set_reminder", mapOf("text" to "   ", "trigger_time" to "2100-01-01T09:00:00"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_reminder missing trigger_time returns error`() = runTest {
        val result = executor.execute(
            ToolCall("3", "set_reminder", mapOf("text" to "call mom"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_reminder invalid trigger_time format returns error`() = runTest {
        val result = executor.execute(
            ToolCall("4", "set_reminder", mapOf("text" to "call mom", "trigger_time" to "not a date"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `set_reminder in the past returns error`() = runTest {
        val result = executor.execute(
            ToolCall("5", "set_reminder", mapOf("text" to "call mom", "trigger_time" to "2000-01-01T09:00:00"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `list_reminders returns upcoming reminders`() = runTest {
        coEvery { dao.listUpcoming(fixedNowMs) } returns listOf(
            ReminderEntity("id-1", "take out trash", fixedNowMs + 1000L, fixedNowMs)
        )

        val result = executor.execute(ToolCall("6", "list_reminders", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"text\":\"take out trash\"")
    }

    @Test
    fun `list_reminders empty returns empty array`() = runTest {
        coEvery { dao.listUpcoming(fixedNowMs) } returns emptyList()

        val result = executor.execute(ToolCall("7", "list_reminders", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("[]")
    }

    @Test
    fun `cancel_reminder deletes and unschedules`() = runTest {
        coEvery { dao.get("id-1") } returns ReminderEntity("id-1", "call mom", fixedNowMs + 1000L, fixedNowMs)

        val result = executor.execute(
            ToolCall("8", "cancel_reminder", mapOf("id" to "id-1"))
        )

        assertThat(result.success).isTrue()
        coVerify { dao.delete("id-1") }
        verify { scheduler.cancel("id-1") }
    }

    @Test
    fun `cancel_reminder not found returns error`() = runTest {
        coEvery { dao.get("nope") } returns null

        val result = executor.execute(
            ToolCall("9", "cancel_reminder", mapOf("id" to "nope"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("10", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
