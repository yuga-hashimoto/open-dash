package com.opendash.app.voice.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TextToSpeech {
    suspend fun speak(text: String)
    fun stop()
    val isSpeaking: StateFlow<Boolean>

    /**
     * Emits the chunk of text that is currently being spoken, enabling
     * "karaoke-style" rolling display in the UI. Providers that stream
     * chunk-by-chunk (e.g. [AndroidTtsProvider]) emit the active sentence
     * on each TTS `onStart` boundary; providers that render in one shot
     * (OpenAI / ElevenLabs / VOICEVOX / Piper) may leave this as the full
     * input text or an empty string.
     *
     * Defaults to an empty, terminal StateFlow so providers that do not
     * implement chunk tracking remain source-compatible.
     */
    val currentChunk: StateFlow<String>
        get() = EMPTY_CHUNK_FLOW

    /**
     * True when this provider streams sentence-level chunks via
     * [currentChunk] during [speak]. [TtsManager] uses this to decide
     * whether to seed the karaoke flow with the full input text up-front
     * (single-shot providers) or rely on the provider's own chunk
     * emissions (streaming providers).
     *
     * Default false keeps single-shot providers (OpenAI / ElevenLabs /
     * VOICEVOX) safe — the manager seeds the full text so the UI never
     * blanks mid-Speaking even though no per-sentence chunk is forthcoming.
     */
    val streamsChunks: Boolean
        get() = false
}

private val EMPTY_CHUNK_FLOW: StateFlow<String> = MutableStateFlow("").asStateFlow()
