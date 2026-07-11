package com.opendash.app.tool.spotify

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultSpotifyAuthManagerTest {

    private lateinit var appPreferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var server: MockWebServer
    private lateinit var manager: DefaultSpotifyAuthManager
    private val moshi = Moshi.Builder().build()
    private val fixedNowMs = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        appPreferences = mockk(relaxed = true)
        securePreferences = mockk(relaxed = true)
        server = MockWebServer()
        server.start()
        manager = DefaultSpotifyAuthManager(
            appPreferences = appPreferences,
            securePreferences = securePreferences,
            client = OkHttpClient(),
            moshi = moshi,
            tokenEndpoint = server.url("/api/token").toString(),
            authorizeEndpoint = server.url("/authorize").toString(),
            clock = { fixedNowMs }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun stubClientId(id: String?) {
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_CLIENT_ID) } returns flowOf(id)
    }

    @Test
    fun `isConfigured is false when client id is unset`() = runTest {
        stubClientId(null)
        assertThat(manager.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured is true when client id is set`() = runTest {
        stubClientId("my-client-id")
        assertThat(manager.isConfigured()).isTrue()
    }

    @Test
    fun `isConnected reflects presence of a stored refresh token`() = runTest {
        every { securePreferences.contains(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN) } returns false
        assertThat(manager.isConnected()).isFalse()

        every { securePreferences.contains(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN) } returns true
        assertThat(manager.isConnected()).isTrue()
    }

    @Test
    fun `buildAuthorizationUrl returns null when not configured`() = runTest {
        stubClientId(null)
        assertThat(manager.buildAuthorizationUrl()).isNull()
    }

    @Test
    fun `buildAuthorizationUrl includes PKCE challenge and persists verifier plus state`() = runTest {
        stubClientId("my-client-id")

        val url = manager.buildAuthorizationUrl()

        assertThat(url).isNotNull()
        assertThat(url).contains("client_id=my-client-id")
        assertThat(url).contains("response_type=code")
        assertThat(url).contains("code_challenge_method=S256")
        assertThat(url).contains("redirect_uri=")
        coVerify { appPreferences.set(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER, any()) }
        coVerify { appPreferences.set(PreferenceKeys.SPOTIFY_PENDING_STATE, any()) }
    }

    @Test
    fun `handleAuthorizationCode rejects state mismatch`() = runTest {
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_STATE) } returns flowOf("expected-state")
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER) } returns flowOf("verifier")

        val result = manager.handleAuthorizationCode("some-code", "wrong-state")

        assertThat(result).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `handleAuthorizationCode rejects missing pending verifier`() = runTest {
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_STATE) } returns flowOf("expected-state")
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER) } returns flowOf(null)

        val result = manager.handleAuthorizationCode("some-code", "expected-state")

        assertThat(result).isFalse()
    }

    @Test
    fun `handleAuthorizationCode exchanges code and stores tokens on success`() = runTest {
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_STATE) } returns flowOf("expected-state")
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER) } returns flowOf("verifier")
        stubClientId("my-client-id")
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"AT1","token_type":"Bearer","expires_in":3600,"refresh_token":"RT1","scope":"x"}"""
            ).setResponseCode(200)
        )

        val result = manager.handleAuthorizationCode("some-code", "expected-state")

        assertThat(result).isTrue()
        coVerify { securePreferences.putString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN, "AT1") }
        coVerify { securePreferences.putString(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN, "RT1") }
        coVerify { appPreferences.set(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS, fixedNowMs + 3_600_000L) }
    }

    @Test
    fun `handleAuthorizationCode returns false on non-2xx exchange response`() = runTest {
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_STATE) } returns flowOf("expected-state")
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER) } returns flowOf("verifier")
        stubClientId("my-client-id")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val result = manager.handleAuthorizationCode("bad-code", "expected-state")

        assertThat(result).isFalse()
    }

    @Test
    fun `getValidAccessToken returns cached token when not expired`() = runTest {
        every { securePreferences.getString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN) } returns "AT1"
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS) } returns flowOf(fixedNowMs + 3_600_000L)

        val token = manager.getValidAccessToken()

        assertThat(token).isEqualTo("AT1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `getValidAccessToken refreshes when expired`() = runTest {
        every { securePreferences.getString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN) } returns "STALE"
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS) } returns flowOf(fixedNowMs - 1000L)
        every { securePreferences.getString(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN) } returns "RT1"
        stubClientId("my-client-id")
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"FRESH","token_type":"Bearer","expires_in":3600}"""
            ).setResponseCode(200)
        )

        val token = manager.getValidAccessToken()

        assertThat(token).isEqualTo("FRESH")
        coVerify { securePreferences.putString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN, "FRESH") }
    }

    @Test
    fun `getValidAccessToken returns null when no refresh token stored`() = runTest {
        every { securePreferences.getString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN) } returns ""
        every { appPreferences.observe(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS) } returns flowOf(0L)
        every { securePreferences.getString(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN) } returns ""

        val token = manager.getValidAccessToken()

        assertThat(token).isNull()
    }

    @Test
    fun `disconnect clears stored tokens and expiry`() = runTest {
        manager.disconnect()

        coVerify { securePreferences.remove(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN) }
        coVerify { securePreferences.remove(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN) }
        coVerify { appPreferences.remove(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS) }
    }
}
