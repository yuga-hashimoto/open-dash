package com.opendash.app.tool.spotify

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Real [SpotifyAuthManager]: Authorization Code + PKCE flow against
 * Spotify Accounts Service. The Client ID is user-supplied (their own
 * Spotify Developer Dashboard app, entered in Settings) — this app
 * ships no embedded Spotify credentials.
 */
class DefaultSpotifyAuthManager(
    private val appPreferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,
    private val authorizeEndpoint: String = DEFAULT_AUTHORIZE_ENDPOINT,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpotifyAuthManager {

    override suspend fun isConfigured(): Boolean =
        !appPreferences.observe(PreferenceKeys.SPOTIFY_CLIENT_ID).first().isNullOrBlank()

    override suspend fun isConnected(): Boolean =
        securePreferences.contains(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN)

    override suspend fun buildAuthorizationUrl(): String? {
        val clientId = currentClientId() ?: return null
        val verifier = PkceGenerator.generateCodeVerifier()
        val challenge = PkceGenerator.generateCodeChallenge(verifier)
        val state = PkceGenerator.generateCodeVerifier().take(16)

        appPreferences.set(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER, verifier)
        appPreferences.set(PreferenceKeys.SPOTIFY_PENDING_STATE, state)

        return authorizeEndpoint.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", clientId)
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", SpotifyAuthManager.REDIRECT_URI)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge)
            addQueryParameter("state", state)
            addQueryParameter("scope", SpotifyAuthManager.SCOPES)
        }.build().toString()
    }

    override suspend fun handleAuthorizationCode(code: String, state: String): Boolean {
        val expectedState = appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_STATE).first()
        val verifier = appPreferences.observe(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER).first()
        appPreferences.remove(PreferenceKeys.SPOTIFY_PENDING_STATE)
        appPreferences.remove(PreferenceKeys.SPOTIFY_PENDING_CODE_VERIFIER)

        if (expectedState == null || expectedState != state) {
            Timber.w("Spotify auth state mismatch (possible CSRF or stale redirect)")
            return false
        }
        if (verifier == null) {
            Timber.w("Spotify auth: no pending PKCE verifier found")
            return false
        }
        val clientId = currentClientId() ?: return false

        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SpotifyAuthManager.REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(tokenEndpoint).post(formBody).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Spotify token exchange failed: ${response.code}")
                        return@use false
                    }
                    val body = response.body?.string() ?: return@use false
                    storeTokens(body) != null
                }
            } catch (e: Exception) {
                Timber.e(e, "Spotify token exchange error")
                false
            }
        }
    }

    override suspend fun getValidAccessToken(): String? {
        val accessToken = securePreferences.getString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN)
        val expiresAt = appPreferences.observe(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS).first() ?: 0L
        if (accessToken.isNotEmpty() && clock() < expiresAt - EXPIRY_SAFETY_MARGIN_MS) {
            return accessToken
        }
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String? {
        val refreshToken = securePreferences.getString(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN)
            .takeIf { it.isNotEmpty() } ?: return null
        val clientId = currentClientId() ?: return null

        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(tokenEndpoint).post(formBody).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Spotify token refresh failed: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string() ?: return@use null
                    storeTokens(body)
                }
            } catch (e: Exception) {
                Timber.e(e, "Spotify token refresh error")
                null
            }
        }
    }

    /** Parses a token-endpoint response, persists it, and returns the access token (null on parse failure). */
    private suspend fun storeTokens(json: String): String? {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<*, *> ?: return null
        val accessToken = root["access_token"] as? String ?: return null
        val expiresIn = (root["expires_in"] as? Number)?.toLong() ?: return null
        // refresh_token is only returned on the initial exchange; a refresh
        // response may omit it, in which case keep the existing one.
        val refreshToken = root["refresh_token"] as? String

        securePreferences.putString(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN, accessToken)
        if (refreshToken != null) {
            securePreferences.putString(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN, refreshToken)
        }
        appPreferences.set(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS, clock() + expiresIn * 1000L)
        return accessToken
    }

    override suspend fun disconnect() {
        securePreferences.remove(SecurePreferences.KEY_SPOTIFY_ACCESS_TOKEN)
        securePreferences.remove(SecurePreferences.KEY_SPOTIFY_REFRESH_TOKEN)
        appPreferences.remove(PreferenceKeys.SPOTIFY_TOKEN_EXPIRES_AT_MS)
    }

    private suspend fun currentClientId(): String? =
        appPreferences.observe(PreferenceKeys.SPOTIFY_CLIENT_ID).first()?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
        const val DEFAULT_TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
        private const val EXPIRY_SAFETY_MARGIN_MS = 60_000L
    }
}
