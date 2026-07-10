package com.opendash.app.assistant.routine

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.voice.routine.RoutineScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RoutineToolExecutorTest {

    private lateinit var store: InMemoryRoutineStore
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var scheduler: RoutineScheduler
    private lateinit var executor: RoutineToolExecutor
    private val fixedNow = LocalDateTime.of(2024, 1, 1, 8, 0) // Monday 08:00

    @BeforeEach
    fun setup() {
        store = InMemoryRoutineStore()
        toolExecutor = mockk()
        scheduler = mockk(relaxed = true)
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        executor = RoutineToolExecutor(store, toolExecutor, moshi, scheduler, nowProvider = { fixedNow })
        coEvery { toolExecutor.execute(any()) } answers {
            val call = firstArg<ToolCall>()
            ToolResult(call.id, true, "ok")
        }
    }

    @Test
    fun `availableTools has four routine tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("create_routine", "run_routine", "list_routines", "delete_routine")
    }

    @Test
    fun `create_routine without schedule persists manual-invoke routine`() = runTest {
        val result = executor.execute(
            ToolCall(
                "c1", "create_routine",
                mapOf(
                    "name" to "good night",
                    "actions_json" to """[{"tool_name":"execute_command","arguments":{"device_id":"l1"}}]"""
                )
            )
        )

        assertThat(result.success).isTrue()
        val saved = store.getByName("good night")
        assertThat(saved).isNotNull()
        assertThat(saved?.actions).hasSize(1)
        assertThat(saved?.actions?.first()?.toolName).isEqualTo("execute_command")
        assertThat(saved?.schedule).isNull()
        verify(exactly = 0) { scheduler.schedule(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `create_routine with schedule persists and schedules`() = runTest {
        val result = executor.execute(
            ToolCall(
                "c2", "create_routine",
                mapOf(
                    "name" to "morning routine",
                    "actions_json" to "[]",
                    "schedule_hour" to 7.0,
                    "schedule_minute" to 0.0,
                    "schedule_repeat_days" to "mon,wed,fri"
                )
            )
        )

        assertThat(result.success).isTrue()
        val saved = store.getByName("morning routine")
        assertThat(saved?.schedule).isEqualTo(RoutineSchedule(7, 0, 21)) // mon=1,wed=4,fri=16 -> 21
        verify { scheduler.schedule(any(), "morning routine", 7, 0, 21, any()) }
    }

    @Test
    fun `create_routine missing name returns error`() = runTest {
        val result = executor.execute(
            ToolCall("c3", "create_routine", mapOf("actions_json" to "[]"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `create_routine missing actions_json returns error`() = runTest {
        val result = executor.execute(
            ToolCall("c4", "create_routine", mapOf("name" to "x"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `create_routine invalid actions_json returns error`() = runTest {
        val result = executor.execute(
            ToolCall("c5", "create_routine", mapOf("name" to "x", "actions_json" to "not json"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `create_routine invalid schedule_repeat_days returns error`() = runTest {
        val result = executor.execute(
            ToolCall(
                "c6", "create_routine",
                mapOf(
                    "name" to "x", "actions_json" to "[]",
                    "schedule_hour" to 7.0, "schedule_minute" to 0.0,
                    "schedule_repeat_days" to "someday"
                )
            )
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `create_routine out of range schedule_hour returns error`() = runTest {
        val result = executor.execute(
            ToolCall(
                "c7", "create_routine",
                mapOf("name" to "x", "actions_json" to "[]", "schedule_hour" to 24.0, "schedule_minute" to 0.0)
            )
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `run_routine executes all actions in order`() = runTest {
        store.save(Routine(
            id = "r1",
            name = "good night",
            description = "Sleep prep",
            actions = listOf(
                RoutineAction("execute_command", mapOf("device_id" to "light1", "action" to "off")),
                RoutineAction("set_volume", mapOf("level" to 10))
            )
        ))

        val result = executor.execute(
            ToolCall("1", "run_routine", mapOf("name" to "good night"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"ran\":2")
        coVerify(exactly = 2) { toolExecutor.execute(any()) }
    }

    @Test
    fun `run_routine name matching is case-insensitive`() = runTest {
        store.save(Routine("r", "Good Night", "", listOf()))

        val result = executor.execute(
            ToolCall("2", "run_routine", mapOf("name" to "good night"))
        )

        assertThat(result.success).isTrue()
    }

    @Test
    fun `run_routine unknown returns error`() = runTest {
        val result = executor.execute(
            ToolCall("3", "run_routine", mapOf("name" to "nope"))
        )

        assertThat(result.success).isFalse()
    }

    @Test
    fun `run_routine counts failures`() = runTest {
        coEvery { toolExecutor.execute(any()) } answers {
            val call = firstArg<ToolCall>()
            if (call.name == "fails") ToolResult(call.id, false, "", "oops")
            else ToolResult(call.id, true, "ok")
        }

        store.save(Routine("r", "mixed", "", listOf(
            RoutineAction("ok_tool", emptyMap()),
            RoutineAction("fails", emptyMap()),
            RoutineAction("ok_tool", emptyMap())
        )))

        val result = executor.execute(
            ToolCall("4", "run_routine", mapOf("name" to "mixed"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.data).contains("\"failures\":1")
    }

    @Test
    fun `list_routines returns all stored`() = runTest {
        store.save(Routine("r1", "first", "desc", listOf()))
        store.save(Routine("r2", "second", "desc2", listOf()))

        val result = executor.execute(ToolCall("5", "list_routines", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("first")
        assertThat(result.data).contains("second")
    }

    @Test
    fun `list_routines escapes control characters to produce valid JSON`() = runTest {
        store.save(
            Routine(
                id = "r1",
                name = "morning\troutine\r\nwith \"quotes\" and \\ slash",
                description = "desc with\ttab",
                actions = listOf(RoutineAction("tool\twith_tab", emptyMap()))
            )
        )

        val result = executor.execute(ToolCall("json", "list_routines", emptyMap()))

        assertThat(result.success).isTrue()
        // Parse with Moshi so the test runs in a JVM-only Android unit test —
        // org.json.JSONArray/JSONObject are not mocked in the Android stub
        // runtime and would throw "Method not mocked" at call time.
        val moshi = Moshi.Builder().build()
        val listAdapter = moshi.adapter<List<Map<String, Any?>>>(
            com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.squareup.moshi.Types.newParameterizedType(
                    Map::class.java,
                    String::class.java,
                    Any::class.java,
                ),
            )
        )
        val parsed = listAdapter.fromJson(result.data) ?: error("Invalid JSON: ${result.data}")
        assertThat(parsed).hasSize(1)
        val entry = parsed[0]
        assertThat(entry["name"])
            .isEqualTo("morning\troutine\r\nwith \"quotes\" and \\ slash")
        assertThat(entry["description"]).isEqualTo("desc with\ttab")
        @Suppress("UNCHECKED_CAST")
        val actions = entry["actions"] as List<Map<String, Any?>>
        assertThat(actions[0]["tool"]).isEqualTo("tool\twith_tab")
    }

    @Test
    fun `delete_routine removes by id`() = runTest {
        store.save(Routine("r", "x", "", listOf()))

        val result = executor.execute(
            ToolCall("6", "delete_routine", mapOf("id" to "r"))
        )

        assertThat(result.success).isTrue()
        assertThat(store.listAll()).isEmpty()
    }

    @Test
    fun `delete_routine cancels scheduler when routine was scheduled`() = runTest {
        store.save(Routine("r", "x", "", listOf(), schedule = RoutineSchedule(7, 0, 0)))

        executor.execute(ToolCall("7", "delete_routine", mapOf("id" to "r")))

        verify { scheduler.cancel("r") }
    }

    @Test
    fun `delete_routine does not touch scheduler for manual-invoke routine`() = runTest {
        store.save(Routine("r", "x", "", listOf()))

        executor.execute(ToolCall("8", "delete_routine", mapOf("id" to "r")))

        verify(exactly = 0) { scheduler.cancel(any()) }
    }
}
