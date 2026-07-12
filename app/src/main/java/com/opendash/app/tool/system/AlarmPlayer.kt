package com.opendash.app.tool.system

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import timber.log.Timber

/**
 * Plays the alarm ringtone for a firing timer.
 *
 * A fresh instance is created per firing timer so multiple concurrent alarms
 * each own their own [MediaPlayer]. [stop] is idempotent.
 *
 * Abstracted as an interface (plus a factory) so [AndroidTimerManager] can be
 * exercised in JVM unit tests without pulling in [MediaPlayer].
 */
interface AlarmPlayer {
    /** Begin looping the alarm sound. Safe to call once per instance. */
    fun startLooping()

    /** Stop playback and release any underlying resources. Idempotent. */
    fun stop()

    /**
     * Adjust playback volume to [volume] (0.0-1.0), e.g. for a gradual
     * fade-in. Default no-op so implementations that don't support
     * runtime volume control (or don't need it, like timers) aren't
     * forced to implement it.
     */
    fun setVolume(volume: Float) {}
}

/** Factory so each firing timer gets its own [AlarmPlayer] instance. */
fun interface AlarmPlayerFactory {
    fun create(): AlarmPlayer
}

/**
 * Default Android implementation. Uses [MediaPlayer] (not [android.media.Ringtone])
 * because `Ringtone.setLooping(...)` requires API 28+ and, in practice,
 * vendor-specific limits have caused the ringtone to silence itself after
 * ~10 s on some devices. [MediaPlayer] gives us reliable looping.
 */
class AndroidMediaPlayerAlarmPlayer(
    private val context: Context
) : AlarmPlayer {

    private var player: MediaPlayer? = null

    override fun startLooping() {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            Timber.w("No alarm/notification/ringtone URI available; cannot play alarm")
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                // setAudioStreamType is deprecated since API 26; we already
                // drive stream selection via setAudioAttributes(USAGE_ALARM)
                // above, which is the modern replacement.
                prepare()
                start()
            }
            player = mp
        } catch (e: Exception) {
            Timber.e(e, "Failed to start alarm MediaPlayer")
            runCatching { player?.release() }
            player = null
        }
    }

    override fun stop() {
        val mp = player ?: return
        player = null
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: Exception) {
            Timber.w(e, "MediaPlayer.stop failed")
        }
        runCatching { mp.release() }
    }

    override fun setVolume(volume: Float) {
        runCatching { player?.setVolume(volume, volume) }
            .onFailure { Timber.w(it, "MediaPlayer.setVolume failed") }
    }
}
