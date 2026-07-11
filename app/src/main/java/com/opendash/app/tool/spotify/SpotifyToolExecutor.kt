package com.opendash.app.tool.spotify

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import timber.log.Timber

/**
 * Spotify search + playback control, via [SpotifyApiClient]. Requires
 * the user to have connected their Spotify account in Settings first
 * (own Client ID + OAuth) — every tool here checks
 * [SpotifyAuthManager.isConnected] up front and returns a clear error
 * pointing at Settings rather than a raw API failure.
 */
class SpotifyToolExecutor(
    private val authManager: SpotifyAuthManager,
    private val apiClient: SpotifyApiClient
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "search_spotify_track",
            description = "Search Spotify for a track by name/artist.",
            parameters = mapOf(
                "query" to ToolParameter("string", "Search query, e.g. 'bohemian rhapsody queen'", required = true)
            )
        ),
        ToolSchema(
            name = "play_spotify_track",
            description = "Search for and play a track on Spotify. Omit query to resume paused playback instead.",
            parameters = mapOf(
                "query" to ToolParameter("string", "Track/artist to search for and play", required = false),
                "device_id" to ToolParameter("string", "Target device id from list_spotify_devices; omit for the active device", required = false)
            )
        ),
        ToolSchema(
            name = "pause_spotify",
            description = "Pause Spotify playback.",
            parameters = mapOf(
                "device_id" to ToolParameter("string", "Target device id; omit for the active device", required = false)
            )
        ),
        ToolSchema(
            name = "spotify_next_track",
            description = "Skip to the next track on Spotify.",
            parameters = mapOf(
                "device_id" to ToolParameter("string", "Target device id; omit for the active device", required = false)
            )
        ),
        ToolSchema(
            name = "spotify_previous_track",
            description = "Skip to the previous track on Spotify.",
            parameters = mapOf(
                "device_id" to ToolParameter("string", "Target device id; omit for the active device", required = false)
            )
        ),
        ToolSchema(
            name = "list_spotify_devices",
            description = "List Spotify Connect devices available to control (needed to target a specific device).",
            parameters = emptyMap()
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "search_spotify_track" -> requireConnected(call) { executeSearch(call) }
            "play_spotify_track" -> requireConnected(call) { executePlay(call) }
            "pause_spotify" -> requireConnected(call) { executePlaybackCommand(call) { deviceId -> apiClient.pause(deviceId) } }
            "spotify_next_track" -> requireConnected(call) { executePlaybackCommand(call) { deviceId -> apiClient.next(deviceId) } }
            "spotify_previous_track" -> requireConnected(call) { executePlaybackCommand(call) { deviceId -> apiClient.previous(deviceId) } }
            "list_spotify_devices" -> requireConnected(call) { executeListDevices(call) }
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Spotify tool failed")
        ToolResult(call.id, false, "", e.message ?: "Spotify error")
    }

    private suspend fun requireConnected(call: ToolCall, block: suspend () -> ToolResult): ToolResult {
        if (!authManager.isConnected()) {
            return ToolResult(call.id, false, "", "Spotify isn't connected. Connect it in Settings first.")
        }
        return block()
    }

    private suspend fun executeSearch(call: ToolCall): ToolResult {
        val query = call.arguments["query"] as? String
            ?: return ToolResult(call.id, false, "", "Missing query")
        val tracks = apiClient.searchTrack(query)
        val data = tracks.joinToString(",") { t ->
            """{"uri":"${t.uri.escapeJson()}","name":"${t.name.escapeJson()}","artist":"${t.artist.escapeJson()}"}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private suspend fun executePlay(call: ToolCall): ToolResult {
        val query = (call.arguments["query"] as? String)?.takeIf { it.isNotBlank() }
        val deviceId = call.arguments["device_id"] as? String

        if (query == null) {
            val ok = apiClient.play(null, deviceId)
            return if (ok) ToolResult(call.id, true, """{"resumed":true}""")
            else ToolResult(call.id, false, "", "Failed to resume playback")
        }

        val match = apiClient.searchTrack(query).firstOrNull()
            ?: return ToolResult(call.id, false, "", "No Spotify track found for: $query")
        val ok = apiClient.play(match.uri, deviceId)
        return if (ok) {
            ToolResult(call.id, true, """{"playing":"${match.name.escapeJson()}","artist":"${match.artist.escapeJson()}"}""")
        } else {
            ToolResult(call.id, false, "", "Failed to start playback")
        }
    }

    private suspend fun executePlaybackCommand(call: ToolCall, command: suspend (String?) -> Boolean): ToolResult {
        val deviceId = call.arguments["device_id"] as? String
        val ok = command(deviceId)
        return if (ok) ToolResult(call.id, true, """{"ok":true}""")
        else ToolResult(call.id, false, "", "Spotify command failed (no active device?)")
    }

    private suspend fun executeListDevices(call: ToolCall): ToolResult {
        val devices = apiClient.listDevices()
        val data = devices.joinToString(",") { d ->
            """{"id":"${d.id.escapeJson()}","name":"${d.name.escapeJson()}","active":${d.isActive}}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private fun String.escapeJson(): String = buildString(length) {
        for (c in this@escapeJson) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
