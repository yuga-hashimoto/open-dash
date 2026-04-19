package com.opendash.app.voice.tts

import android.content.Context
import com.opendash.app.data.preferences.AppPreferences
import okhttp3.OkHttpClient

/**
 * `standard` flavor stub: embedded VOICEVOX is not shipped in this build,
 * so this returns null and TtsManager falls back to the HTTP-engine provider.
 *
 * The matching `full` flavor implementation creates a real
 * [EmbeddedVoiceVoxTtsProvider]. Both files share this fully-qualified name
 * so [TtsManager] compiles identically across flavors.
 */
object VoiceVoxEmbeddedFactory {
    @Suppress("UNUSED_PARAMETER")
    fun createOrNull(
        context: Context,
        preferences: AppPreferences,
        httpClient: OkHttpClient,
    ): TextToSpeech? = null
}
