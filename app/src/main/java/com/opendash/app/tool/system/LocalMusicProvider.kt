package com.opendash.app.tool.system

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import timber.log.Timber

/** A track found in the on-device music library. */
data class LocalTrack(val uri: String, val title: String, val artist: String)

/**
 * Searches the on-device music library (MediaStore) — the zero-config
 * fallback for `play_music` when no external music app is installed to
 * handle a search intent, and no smart-home media_player is configured.
 * Mirrors [AndroidPhotosProvider]'s ContentResolver query pattern.
 */
interface LocalMusicProvider {
    /** Empty [query] matches any track. Results ordered by title. */
    suspend fun findTracks(query: String, limit: Int = 5): List<LocalTrack>
    fun hasPermission(): Boolean
}

class AndroidLocalMusicProvider(
    private val context: Context
) : LocalMusicProvider {

    override suspend fun findTracks(query: String, limit: Int): List<LocalTrack> {
        if (!hasPermission()) return emptyList()

        val tracks = mutableListOf<LocalTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val (selection, selectionArgs) = if (query.isBlank()) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0" to null
        } else {
            val like = "%$query%"
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)" to
                arrayOf(like, like)
        }
        val boundedLimit = limit.coerceIn(1, 20)

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.TITLE} ASC LIMIT $boundedLimit"
            )?.use { cursor ->
                while (cursor.moveToNext() && tracks.size < boundedLimit) {
                    val id = cursor.getLong(0)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                    val title = cursor.getString(1).orEmpty()
                    val artist = cursor.getString(2).orEmpty()
                    tracks.add(LocalTrack(uri, title, artist))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to query local music library")
        }

        return tracks
    }

    override fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
