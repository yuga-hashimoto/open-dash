package com.opendash.app.tool.info

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SportsToolExecutorTest {

    private lateinit var provider: SportsScoreProvider
    private lateinit var executor: SportsToolExecutor

    @BeforeEach
    fun setup() {
        provider = mockk()
        executor = SportsToolExecutor(provider)
    }

    @Test
    fun `availableTools exposes get_sports_scores`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly("get_sports_scores")
    }

    @Test
    fun `get_sports_scores returns all games for a league`() = runTest {
        coEvery { provider.getScores("nba") } returns listOf(
            GameScore("San Antonio Spurs", 90, "New York Knicks", 94, "Final"),
            GameScore("Lakers", 101, "Celtics", 99, "In Progress")
        )

        val result = executor.execute(ToolCall("1", "get_sports_scores", mapOf("league" to "nba")))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"home_team\":\"San Antonio Spurs\"")
        assertThat(result.data).contains("\"away_team\":\"New York Knicks\"")
        assertThat(result.data).contains("\"status\":\"Final\"")
        assertThat(result.data).contains("Lakers")
    }

    @Test
    fun `get_sports_scores filters by team name case-insensitively`() = runTest {
        coEvery { provider.getScores("nba") } returns listOf(
            GameScore("San Antonio Spurs", 90, "New York Knicks", 94, "Final"),
            GameScore("Lakers", 101, "Celtics", 99, "In Progress")
        )

        val result = executor.execute(
            ToolCall("2", "get_sports_scores", mapOf("league" to "nba", "team" to "lakers"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Lakers")
        assertThat(result.data).doesNotContain("Knicks")
    }

    @Test
    fun `get_sports_scores no matching team returns empty array`() = runTest {
        coEvery { provider.getScores("nba") } returns listOf(
            GameScore("San Antonio Spurs", 90, "New York Knicks", 94, "Final")
        )

        val result = executor.execute(
            ToolCall("3", "get_sports_scores", mapOf("league" to "nba", "team" to "nonexistent"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("[]")
    }

    @Test
    fun `get_sports_scores missing league returns error`() = runTest {
        val result = executor.execute(ToolCall("4", "get_sports_scores", emptyMap()))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `get_sports_scores unknown league surfaces provider error`() = runTest {
        coEvery { provider.getScores("curling") } throws IllegalArgumentException("Unknown league: curling")

        val result = executor.execute(ToolCall("5", "get_sports_scores", mapOf("league" to "curling")))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("6", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
