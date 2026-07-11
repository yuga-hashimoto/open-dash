package com.opendash.app.assistant.routine

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.RoutineDao
import com.opendash.app.data.db.RoutineEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomRoutineStoreTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private lateinit var dao: RoutineDao
    private lateinit var store: RoomRoutineStore

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        store = RoomRoutineStore(dao, moshi)
    }

    @Test
    fun `save round-trips actions via JSON`() = runTest {
        val actions = listOf(
            RoutineAction("execute_command", mapOf("device_id" to "l1", "action" to "off")),
            RoutineAction("set_volume", mapOf("level" to 10.0))
        )
        var saved: RoutineEntity? = null
        coEvery { dao.upsert(any()) } answers { saved = firstArg() }

        store.save(Routine("r1", "good night", "desc", actions))

        assertThat(saved).isNotNull()
        assertThat(saved!!.name).isEqualTo("good night")
        assertThat(saved!!.actionsJson).contains("execute_command")
        assertThat(saved!!.actionsJson).contains("set_volume")
    }

    @Test
    fun `save generates id when blank`() = runTest {
        var saved: RoutineEntity? = null
        coEvery { dao.upsert(any()) } answers { saved = firstArg() }

        store.save(Routine(id = "", name = "x", description = "", actions = emptyList()))

        assertThat(saved?.id).isNotEmpty()
    }

    @Test
    fun `get returns domain object with parsed actions`() = runTest {
        val json = """[{"toolName":"execute_command","arguments":{"device_id":"l1"},"delayMs":0}]"""
        coEvery { dao.get("r1") } returns RoutineEntity("r1", "n", "d", json, 0L)

        val routine = store.get("r1")

        assertThat(routine).isNotNull()
        assertThat(routine?.actions).hasSize(1)
        assertThat(routine?.actions?.first()?.toolName).isEqualTo("execute_command")
    }

    @Test
    fun `getByName lowercased match`() = runTest {
        val json = "[]"
        coEvery { dao.getByName("Good Night") } returns RoutineEntity("r", "Good Night", "", json, 0L)

        assertThat(store.getByName("Good Night")?.name).isEqualTo("Good Night")
    }

    @Test
    fun `delete returns true when dao removes row`() = runTest {
        coEvery { dao.delete("r1") } returns 1
        assertThat(store.delete("r1")).isTrue()

        coEvery { dao.delete("missing") } returns 0
        assertThat(store.delete("missing")).isFalse()
    }

    @Test
    fun `corrupted json falls back to empty actions`() = runTest {
        coEvery { dao.get("r1") } returns RoutineEntity("r1", "n", "d", "not-json", 0L)

        val routine = store.get("r1")
        assertThat(routine).isNotNull()
        assertThat(routine?.actions).isEmpty()
    }

    @Test
    fun `save persists schedule fields`() = runTest {
        var saved: RoutineEntity? = null
        coEvery { dao.upsert(any()) } answers { saved = firstArg() }

        store.save(Routine("r1", "morning", "desc", emptyList(), schedule = RoutineSchedule(7, 30, 21)))

        assertThat(saved?.scheduleHour).isEqualTo(7)
        assertThat(saved?.scheduleMinute).isEqualTo(30)
        assertThat(saved?.scheduleRepeatDaysMask).isEqualTo(21)
    }

    @Test
    fun `save with no schedule persists null schedule fields`() = runTest {
        var saved: RoutineEntity? = null
        coEvery { dao.upsert(any()) } answers { saved = firstArg() }

        store.save(Routine("r1", "manual", "desc", emptyList()))

        assertThat(saved?.scheduleHour).isNull()
        assertThat(saved?.scheduleMinute).isNull()
        assertThat(saved?.scheduleRepeatDaysMask).isNull()
    }

    @Test
    fun `get reconstructs schedule from entity`() = runTest {
        coEvery { dao.get("r1") } returns RoutineEntity(
            "r1", "morning", "d", "[]", 0L,
            scheduleHour = 7, scheduleMinute = 30, scheduleRepeatDaysMask = 21
        )

        val routine = store.get("r1")

        assertThat(routine?.schedule).isEqualTo(RoutineSchedule(7, 30, 21))
    }

    @Test
    fun `get with no schedule columns returns null schedule`() = runTest {
        coEvery { dao.get("r1") } returns RoutineEntity("r1", "manual", "d", "[]", 0L)

        val routine = store.get("r1")

        assertThat(routine?.schedule).isNull()
    }
}
