package com.opendash.app.voice.wakeword.openwakeword

/**
 * The three ONNX files openWakeWord needs: a shared melspectrogram
 * front-end, a shared speech-embedding backbone, and a per-keyword
 * classifier. All three come from the same upstream release so their
 * tensor shapes are guaranteed compatible with each other.
 *
 * Only "hey jarvis" is offered: openWakeWord's pre-trained models are
 * English-only presets (alexa / hey mycroft / hey jarvis / hey rhasspy
 * / current weather / timers) with no custom-keyword or Japanese
 * support, unlike this app's default Vosk detector (arbitrary keyword
 * text, any language Vosk's ASR model covers). "Hey jarvis" was picked
 * as the least trademark-adjacent common preset. Users who want a
 * custom keyword or Japanese wake word must stay on Vosk — this is an
 * opt-in alternative engine, not a replacement.
 *
 * URLs/sizes/hashes verified against the actual v0.5.1 release assets
 * (github.com/dscripka/openWakeWord/releases/tag/v0.5.1) during
 * implementation — not guessed.
 */
data class OpenWakeWordModelFile(
    val id: String,
    val url: String,
    val filename: String,
    val sha256: String,
    val sizeBytes: Long
)

object OpenWakeWordModelCatalog {

    private const val BASE =
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

    val MELSPECTROGRAM = OpenWakeWordModelFile(
        id = "melspectrogram",
        url = "$BASE/melspectrogram.onnx",
        filename = "melspectrogram.onnx",
        sha256 = "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
        sizeBytes = 1_087_958L
    )

    val EMBEDDING = OpenWakeWordModelFile(
        id = "embedding",
        url = "$BASE/embedding_model.onnx",
        filename = "embedding_model.onnx",
        sha256 = "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
        sizeBytes = 1_326_578L
    )

    val HEY_JARVIS = OpenWakeWordModelFile(
        id = "hey_jarvis",
        url = "$BASE/hey_jarvis_v0.1.onnx",
        filename = "hey_jarvis_v0.1.onnx",
        sha256 = "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
        sizeBytes = 1_271_370L
    )

    /** All three must be present before [OpenWakeWordDetector] can run. */
    val all: List<OpenWakeWordModelFile> = listOf(MELSPECTROGRAM, EMBEDDING, HEY_JARVIS)
}
