package com.opendash.app.tool.system

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import timber.log.Timber

/**
 * Plays a single track from the on-device music library. No queue —
 * [NativeMediaPlayerToolExecutor] treats next/previous as unsupported
 * while a local track is active rather than pretending to skip.
 * Abstracted so the executor is unit-testable without [MediaPlayer].
 */
interface LocalMusicPlayer {
    fun play(uri: String)
    fun pause()
    fun resume()
    fun stop()
    /** True once [play] has succeeded and [stop] hasn't been called since. */
    fun isActive(): Boolean
}

class AndroidLocalMusicPlayer(
    private val context: Context
) : LocalMusicPlayer {

    private var player: MediaPlayer? = null

    override fun play(uri: String) {
        stop()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, Uri.parse(uri))
                prepare()
                start()
            }
            player = mp
        } catch (e: Exception) {
            Timber.e(e, "Failed to play local track $uri")
            runCatching { player?.release() }
            player = null
        }
    }

    override fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
            .onFailure { Timber.w(it, "Failed to pause local playback") }
    }

    override fun resume() {
        runCatching { player?.start() }
            .onFailure { Timber.w(it, "Failed to resume local playback") }
    }

    override fun stop() {
        val mp = player ?: return
        player = null
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }

    override fun isActive(): Boolean = player != null
}
