package com.opendash.app.tool.spotify

/**
 * Manages the Spotify OAuth Authorization Code + PKCE flow: building
 * the authorization URL, exchanging the redirect's `code` for tokens,
 * transparently refreshing an expired access token, and reporting
 * connection state. See [DefaultSpotifyAuthManager] for the real
 * implementation.
 */
interface SpotifyAuthManager {
    /** True once the user has entered their own Spotify Client ID in Settings. */
    suspend fun isConfigured(): Boolean

    /** True once a token exchange has succeeded and hasn't been disconnected. */
    suspend fun isConnected(): Boolean

    /**
     * Builds the `https://accounts.spotify.com/authorize` URL to open in a
     * browser, persisting the PKCE code_verifier + CSRF state for
     * [handleAuthorizationCode] to consume later. Returns null if
     * [isConfigured] is false.
     */
    suspend fun buildAuthorizationUrl(): String?

    /**
     * Completes the flow: verifies [state] matches what
     * [buildAuthorizationUrl] persisted, exchanges [code] for tokens,
     * and stores them. Returns true on success.
     */
    suspend fun handleAuthorizationCode(code: String, state: String): Boolean

    /**
     * Returns a currently-valid access token, refreshing it first if
     * expired. Returns null if not connected or the refresh fails
     * (e.g. the user revoked access from Spotify's side).
     */
    suspend fun getValidAccessToken(): String?

    /** Clears stored tokens. Does not revoke them on Spotify's side. */
    suspend fun disconnect()

    companion object {
        /**
         * Must be registered as a Redirect URI on the user's own Spotify
         * Developer Dashboard app (exact string match required by
         * Spotify) and matches this app's manifest intent-filter.
         */
        const val REDIRECT_URI = "opendash-spotify://callback"

        const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"
    }
}
