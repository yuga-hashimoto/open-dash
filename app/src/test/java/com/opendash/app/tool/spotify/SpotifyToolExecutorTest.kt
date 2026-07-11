package com.opendash.app.tool.spotify

import com.google.common.truth.Truth.assertThat
import com.opendash.app.tool.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpotifyToolExecutorTest {

    private lateinit var authManager: SpotifyAuthManager
    private lateinit var apiClient: SpotifyApiClient
    private lateinit var executor: SpotifyToolExecutor

    @BeforeEach
    fun setup() {
        authManager = mockk()
        apiClient = mockk()
        executor = SpotifyToolExecutor(authManager, apiClient)
        coEvery { authManager.isConnected() } returns true
    }

    @Test
    fun `availableTools exposes all spotify tools`() = runTest {
        val names = executor.availableTools().map { it.name }
        assertThat(names).containsExactly(
            "search_spotify_track", "play_spotify_track", "pause_spotify",
            "spotify_next_track", "spotify_previous_track", "list_spotify_devices"
        )
    }

    @Test
    fun `search_spotify_track returns matches`() = runTest {
        coEvery { apiClient.searchTrack("bohemian rhapsody") } returns listOf(
            SpotifyTrack("spotify:track:1", "Bohemian Rhapsody", "Queen")
        )

        val result = executor.execute(ToolCall("1", "search_spotify_track", mapOf("query" to "bohemian rhapsody")))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"name\":\"Bohemian Rhapsody\"")
        assertThat(result.data).contains("\"artist\":\"Queen\"")
    }

    @Test
    fun `search_spotify_track when not connected returns error`() = runTest {
        coEvery { authManager.isConnected() } returns false

        val result = executor.execute(ToolCall("2", "search_spotify_track", mapOf("query" to "queen")))

        assertThat(result.success).isFalse()
        coVerify(exactly = 0) { apiClient.searchTrack(any()) }
    }

    @Test
    fun `play_spotify_track searches and plays the first match`() = runTest {
        coEvery { apiClient.searchTrack("bohemian rhapsody") } returns listOf(
            SpotifyTrack("spotify:track:1", "Bohemian Rhapsody", "Queen")
        )
        coEvery { apiClient.play("spotify:track:1", null) } returns true

        val result = executor.execute(ToolCall("3", "play_spotify_track", mapOf("query" to "bohemian rhapsody")))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Bohemian Rhapsody")
    }

    @Test
    fun `play_spotify_track with no query resumes playback`() = runTest {
        coEvery { apiClient.play(null, null) } returns true

        val result = executor.execute(ToolCall("4", "play_spotify_track", emptyMap()))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `play_spotify_track no search results returns error`() = runTest {
        coEvery { apiClient.searchTrack("nonexistent song xyz") } returns emptyList()

        val result = executor.execute(ToolCall("5", "play_spotify_track", mapOf("query" to "nonexistent song xyz")))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `play_spotify_track passes through device_id`() = runTest {
        coEvery { apiClient.play(null, "device-42") } returns true

        val result = executor.execute(
            ToolCall("6", "play_spotify_track", mapOf("device_id" to "device-42"))
        )

        assertThat(result.success).isTrue()
        coVerify { apiClient.play(null, "device-42") }
    }

    @Test
    fun `pause_spotify calls apiClient pause`() = runTest {
        coEvery { apiClient.pause(null) } returns true

        val result = executor.execute(ToolCall("7", "pause_spotify", emptyMap()))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `spotify_next_track calls apiClient next`() = runTest {
        coEvery { apiClient.next(null) } returns true

        val result = executor.execute(ToolCall("8", "spotify_next_track", emptyMap()))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `spotify_previous_track calls apiClient previous`() = runTest {
        coEvery { apiClient.previous(null) } returns true

        val result = executor.execute(ToolCall("9", "spotify_previous_track", emptyMap()))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `playback command failure returns error result`() = runTest {
        coEvery { apiClient.pause(null) } returns false

        val result = executor.execute(ToolCall("10", "pause_spotify", emptyMap()))

        assertThat(result.success).isFalse()
    }

    @Test
    fun `list_spotify_devices returns devices`() = runTest {
        coEvery { apiClient.listDevices() } returns listOf(SpotifyDevice("d1", "Kitchen Speaker", true))

        val result = executor.execute(ToolCall("11", "list_spotify_devices", emptyMap()))

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Kitchen Speaker")
    }

    @Test
    fun `unknown tool returns error`() = runTest {
        val result = executor.execute(ToolCall("12", "not_a_tool", emptyMap()))

        assertThat(result.success).isFalse()
    }
}
