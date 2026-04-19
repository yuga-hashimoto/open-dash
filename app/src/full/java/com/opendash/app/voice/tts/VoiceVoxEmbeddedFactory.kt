package com.opendash.app.voice.tts

import android.content.Context
import com.opendash.app.data.preferences.AppPreferences
import okhttp3.OkHttpClient

/**
 * `full` flavor implementation: returns a real [EmbeddedVoiceVoxTtsProvider]
 * backed by the bundled voicevox_core AAR and libvoicevox_onnxruntime.so.
 *
 * The matching `standard` flavor stub returns null so builds without the
 * native runtime fall back to the HTTP-engine provider.
 */
object VoiceVoxEmbeddedFactory {
    fun createOrNull(
        context: Context,
        preferences: AppPreferences,
        httpClient: OkHttpClient,
    ): TextToSpeech = EmbeddedVoiceVoxTtsProvider(context, preferences, httpClient)
}
