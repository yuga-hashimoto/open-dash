package com.opendash.app.tool.system

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import timber.log.Timber

/**
 * Plays and controls music via the device's default music app — no Home
 * Assistant required.
 *
 * Dispatch strategy:
 * - `play` with a `query` → [MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH]
 *   (Spotify / YouTube Music / Play Music / Amazon Music all register for
 *   this) when [canResolveActivity] says an app can handle it.
 * - `play` with a `query` and no app installed to handle it → falls back to
 *   [localMusicProvider] (on-device MediaStore library) via [localMusicPlayer]
 *   — this is the true zero-config path: it works with nothing configured
 *   (no Home Assistant, no Spotify credentials, no third-party music app).
 * - `play` without a query → resumes [localMusicPlayer] if it's the one
 *   currently active, otherwise [KeyEvent.KEYCODE_MEDIA_PLAY] (resume
 *   whatever was last playing on the focused media session).
 * - `pause` → routes to [localMusicPlayer] if active, otherwise the matching
 *   `KEYCODE_MEDIA_PAUSE` dispatched through [AudioManager.dispatchMediaKeyEvent].
 * - `next` / `previous` → unsupported while playing from the local library
 *   (no queue, single track only — see [LocalMusicPlayer]); otherwise the
 *   matching `KEYCODE_MEDIA_*` event via [AudioManager.dispatchMediaKeyEvent],
 *   which targets the currently focused media session regardless of which
 *   app owns it.
 *
 * `intentFactory`, `keyEventDispatcher`, and `canResolveActivity` are
 * injected so unit tests can verify dispatch without Robolectric.
 */
class NativeMediaPlayerToolExecutor(
    private val context: Context,
    private val intentFactory: (String) -> Intent = { query ->
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
        }
    },
    private val keyEventDispatcher: (Int) -> Boolean = { keyCode ->
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            false
        } else {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }
    },
    private val canResolveActivity: (Intent) -> Boolean = { intent ->
        intent.resolveActivity(context.packageManager) != null
    },
    private val localMusicProvider: LocalMusicProvider = AndroidLocalMusicProvider(context),
    private val localMusicPlayer: LocalMusicPlayer = AndroidLocalMusicPlayer(context)
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "play_music",
            description = "Play or control music using the device's default music app " +
                "(Spotify, YouTube Music, etc.) when one is installed; falls back to the " +
                "on-device music library when no app can handle the request, so this " +
                "always works even with nothing configured. Use action='play' with an " +
                "optional query to search and play a specific song/artist/album; omit " +
                "the query to resume. Use pause/next/previous to control the currently " +
                "playing session — next/previous aren't supported while playing from " +
                "the local library (no queue, single track only).",
            parameters = mapOf(
                "action" to ToolParameter(
                    type = "string",
                    description = "One of play, pause, next, previous",
                    required = true,
                    enum = ACTIONS.toList()
                ),
                "query" to ToolParameter(
                    type = "string",
                    description = "Optional search query (song/artist/album). " +
                        "Used only with action='play'. Empty query = play anything.",
                    required = false
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        return try {
            when (call.name) {
                "play_music" -> executePlayMusic(call)
                else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
            }
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app registered for MEDIA_PLAY_FROM_SEARCH")
            ToolResult(
                call.id,
                false,
                "",
                "No music app is installed to handle this request."
            )
        } catch (e: Exception) {
            Timber.e(e, "play_music failed")
            ToolResult(call.id, false, "", e.message ?: "play_music failed")
        }
    }

    private suspend fun executePlayMusic(call: ToolCall): ToolResult {
        val action = (call.arguments["action"] as? String)?.trim()?.lowercase()
            ?: return ToolResult(call.id, false, "", "Missing action parameter")
        if (action !in ACTIONS) {
            return ToolResult(
                call.id,
                false,
                "",
                "Unsupported action '$action'. Expected one of: ${ACTIONS.joinToString()}"
            )
        }

        return when (action) {
            "play" -> dispatchPlay(call)
            "pause" -> dispatchPause(call)
            "next" -> dispatchSkip(call, KeyEvent.KEYCODE_MEDIA_NEXT, "next")
            "previous" -> dispatchSkip(call, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous")
            else -> ToolResult(call.id, false, "", "Unsupported action: $action")
        }
    }

    private suspend fun dispatchPlay(call: ToolCall): ToolResult {
        val query = (call.arguments["query"] as? String)?.trim().orEmpty()
        if (query.isEmpty()) {
            if (localMusicPlayer.isActive()) {
                localMusicPlayer.resume()
                return ToolResult(call.id, true, """{"action":"play","source":"local"}""")
            }
            return dispatchKey(call, KeyEvent.KEYCODE_MEDIA_PLAY, "play")
        }

        val intent = intentFactory(query).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (canResolveActivity(intent)) {
            context.startActivity(intent)
            Timber.d("play_music: started PLAY_FROM_SEARCH query='$query'")
            return ToolResult(
                call.id, true,
                """{"action":"play","query":${quoteJson(query)},"source":"app"}"""
            )
        }

        // No music app installed to handle the search — the zero-config
        // fallback so "play music" still does something useful.
        val track = localMusicProvider.findTracks(query, limit = 1).firstOrNull()
            ?: return ToolResult(
                call.id, false, "",
                "No music app installed and no matching track in the local library for '$query'."
            )
        localMusicPlayer.play(track.uri)
        Timber.d("play_music: falling back to local library, now playing '${track.title}'")
        return ToolResult(
            call.id, true,
            """{"action":"play","query":${quoteJson(query)},"source":"local","now_playing":${quoteJson(track.title)}}"""
        )
    }

    private fun dispatchPause(call: ToolCall): ToolResult {
        if (localMusicPlayer.isActive()) {
            localMusicPlayer.pause()
            return ToolResult(call.id, true, """{"action":"pause","source":"local"}""")
        }
        return dispatchKey(call, KeyEvent.KEYCODE_MEDIA_PAUSE, "pause")
    }

    private fun dispatchSkip(call: ToolCall, keyCode: Int, action: String): ToolResult {
        if (localMusicPlayer.isActive()) {
            return ToolResult(
                call.id, false, "",
                "Skipping tracks isn't supported while playing from the local library."
            )
        }
        return dispatchKey(call, keyCode, action)
    }

    private fun dispatchKey(call: ToolCall, keyCode: Int, action: String): ToolResult {
        val ok = keyEventDispatcher(keyCode)
        return if (ok) {
            Timber.d("play_music: dispatched key event for action=$action")
            ToolResult(call.id, true, """{"action":"$action"}""")
        } else {
            ToolResult(
                call.id,
                false,
                "",
                "Audio service unavailable; could not dispatch $action."
            )
        }
    }

    private fun quoteJson(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        val ACTIONS: Set<String> = setOf("play", "pause", "next", "previous")
    }
}
