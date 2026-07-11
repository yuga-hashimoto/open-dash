package com.opendash.app.tool.spotify

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

class DefaultSpotifyApiClient(
    private val authManager: SpotifyAuthManager,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String = DEFAULT_BASE_URL
) : SpotifyApiClient {

    override suspend fun searchTrack(query: String): List<SpotifyTrack> {
        val token = authManager.getValidAccessToken() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("type", "track")
                .addQueryParameter("limit", "5")
                .build()
            val request = authorizedRequest(url.toString(), token).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseTracks(body)
                }
            } catch (e: Exception) {
                Timber.e(e, "Spotify search failed")
                emptyList()
            }
        }
    }

    override suspend fun play(trackUri: String?, deviceId: String?): Boolean {
        val bodyJson = if (trackUri != null) """{"uris":["${trackUri}"]}""" else "{}"
        return sendPlayerCommand("play", "PUT", deviceId, bodyJson)
    }

    override suspend fun pause(deviceId: String?): Boolean = sendPlayerCommand("pause", "PUT", deviceId, null)

    override suspend fun next(deviceId: String?): Boolean = sendPlayerCommand("next", "POST", deviceId, null)

    override suspend fun previous(deviceId: String?): Boolean = sendPlayerCommand("previous", "POST", deviceId, null)

    override suspend fun listDevices(): List<SpotifyDevice> {
        val token = authManager.getValidAccessToken() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val request = authorizedRequest("$baseUrl/me/player/devices", token).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseDevices(body)
                }
            } catch (e: Exception) {
                Timber.e(e, "Spotify listDevices failed")
                emptyList()
            }
        }
    }

    private suspend fun sendPlayerCommand(path: String, method: String, deviceId: String?, bodyJson: String?): Boolean {
        val token = authManager.getValidAccessToken() ?: return false
        return withContext(Dispatchers.IO) {
            val urlBuilder = "$baseUrl/me/player/$path".toHttpUrl().newBuilder()
            if (deviceId != null) urlBuilder.addQueryParameter("device_id", deviceId)

            val requestBody = bodyJson?.toRequestBody(JSON_MEDIA_TYPE)
            val builder = authorizedRequest(urlBuilder.build().toString(), token)
            when (method) {
                "PUT" -> builder.put(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                "POST" -> builder.post(requestBody ?: "".toRequestBody(JSON_MEDIA_TYPE))
                else -> error("Unsupported method: $method")
            }

            try {
                client.newCall(builder.build()).execute().use { response -> response.isSuccessful }
            } catch (e: Exception) {
                Timber.e(e, "Spotify player command '$path' failed")
                false
            }
        }
    }

    private fun authorizedRequest(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun parseTracks(json: String): List<SpotifyTrack> {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<*, *> ?: return emptyList()
        val items = (root["tracks"] as? Map<*, *>)?.get("items") as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val trackMap = item as? Map<*, *> ?: return@mapNotNull null
            val uri = trackMap["uri"] as? String ?: return@mapNotNull null
            val name = trackMap["name"] as? String ?: return@mapNotNull null
            val artists = trackMap["artists"] as? List<*>
            val artist = (artists?.firstOrNull() as? Map<*, *>)?.get("name") as? String ?: "Unknown"
            SpotifyTrack(uri, name, artist)
        }
    }

    private fun parseDevices(json: String): List<SpotifyDevice> {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<*, *> ?: return emptyList()
        val devices = root["devices"] as? List<*> ?: return emptyList()
        return devices.mapNotNull { item ->
            val deviceMap = item as? Map<*, *> ?: return@mapNotNull null
            val id = deviceMap["id"] as? String ?: return@mapNotNull null
            val name = deviceMap["name"] as? String ?: return@mapNotNull null
            val isActive = deviceMap["is_active"] as? Boolean ?: false
            SpotifyDevice(id, name, isActive)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.spotify.com/v1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
