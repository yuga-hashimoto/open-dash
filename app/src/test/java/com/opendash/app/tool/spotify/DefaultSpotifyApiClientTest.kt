package com.opendash.app.tool.spotify

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultSpotifyApiClientTest {

    private lateinit var authManager: SpotifyAuthManager
    private lateinit var server: MockWebServer
    private lateinit var apiClient: DefaultSpotifyApiClient
    private val moshi = Moshi.Builder().build()

    @BeforeEach
    fun setUp() {
        authManager = mockk()
        server = MockWebServer()
        server.start()
        apiClient = DefaultSpotifyApiClient(
            authManager = authManager,
            client = OkHttpClient(),
            moshi = moshi,
            baseUrl = server.url("/v1").toString().removeSuffix("/")
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `searchTrack returns parsed tracks and sends bearer token`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(
            MockResponse().setBody(
                """{"tracks":{"items":[
                    {"uri":"spotify:track:1","name":"Song A","artists":[{"name":"Artist A"}]},
                    {"uri":"spotify:track:2","name":"Song B","artists":[{"name":"Artist B"},{"name":"Artist C"}]}
                ]}}"""
            ).setResponseCode(200)
        )

        val tracks = apiClient.searchTrack("song")

        assertThat(tracks).hasSize(2)
        assertThat(tracks[0]).isEqualTo(SpotifyTrack("spotify:track:1", "Song A", "Artist A"))
        assertThat(tracks[1]).isEqualTo(SpotifyTrack("spotify:track:2", "Song B", "Artist B"))
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer AT1")
    }

    @Test
    fun `searchTrack returns empty list when not connected`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns null

        val tracks = apiClient.searchTrack("song")

        assertThat(tracks).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `play with track uri sends PUT with uris body`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(204))

        val result = apiClient.play(trackUri = "spotify:track:1", deviceId = null)

        assertThat(result).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/v1/me/player/play")
        assertThat(recorded.body.readUtf8()).contains("spotify:track:1")
    }

    @Test
    fun `play with device id includes device_id query param`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(204))

        apiClient.play(trackUri = null, deviceId = "device-42")

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("device_id=device-42")
    }

    @Test
    fun `play returns false when not connected`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns null

        val result = apiClient.play(trackUri = null, deviceId = null)

        assertThat(result).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `pause sends PUT to pause endpoint`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(204))

        val result = apiClient.pause(deviceId = null)

        assertThat(result).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("PUT")
        assertThat(recorded.path).isEqualTo("/v1/me/player/pause")
    }

    @Test
    fun `next sends POST to next endpoint`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(204))

        val result = apiClient.next(deviceId = null)

        assertThat(result).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/me/player/next")
    }

    @Test
    fun `previous sends POST to previous endpoint`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(204))

        val result = apiClient.previous(deviceId = null)

        assertThat(result).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/me/player/previous")
    }

    @Test
    fun `listDevices returns parsed devices`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(
            MockResponse().setBody(
                """{"devices":[
                    {"id":"d1","name":"Kitchen Speaker","is_active":true},
                    {"id":"d2","name":"Phone","is_active":false}
                ]}"""
            ).setResponseCode(200)
        )

        val devices = apiClient.listDevices()

        assertThat(devices).containsExactly(
            SpotifyDevice("d1", "Kitchen Speaker", true),
            SpotifyDevice("d2", "Phone", false)
        )
    }

    @Test
    fun `playback command returns false on non-2xx response`() = runTest {
        coEvery { authManager.getValidAccessToken() } returns "AT1"
        server.enqueue(MockResponse().setResponseCode(404))

        val result = apiClient.pause(deviceId = null)

        assertThat(result).isFalse()
    }
}
