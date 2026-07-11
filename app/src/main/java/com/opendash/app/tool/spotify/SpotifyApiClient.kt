package com.opendash.app.tool.spotify

data class SpotifyTrack(
    val uri: String,
    val name: String,
    val artist: String
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val isActive: Boolean
)

/**
 * Spotify Web API playback control — targets whichever device is
 * currently active in the user's Spotify account (their phone, a
 * Spotify Connect speaker, etc., per [listDevices]), the same way
 * Alexa's Spotify Connect integration works. This app does not embed
 * the Spotify Android SDK / App Remote, so it cannot play audio
 * locally through its own process.
 */
interface SpotifyApiClient {
    /** Empty list if not connected or the search fails. */
    suspend fun searchTrack(query: String): List<SpotifyTrack>

    /** Starts [trackUri] (or resumes playback if null) on [deviceId] (or the active device if null). */
    suspend fun play(trackUri: String?, deviceId: String?): Boolean
    suspend fun pause(deviceId: String?): Boolean
    suspend fun next(deviceId: String?): Boolean
    suspend fun previous(deviceId: String?): Boolean

    /** Empty list if not connected or no devices are available. */
    suspend fun listDevices(): List<SpotifyDevice>
}
