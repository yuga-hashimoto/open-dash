package com.opendash.app.voice.stt.whisper

/**
 * Curated list of whisper.cpp GGML models suitable for an Android tablet.
 *
 * Kept intentionally small and hardcoded — we don't query HuggingFace
 * dynamically the way [com.opendash.app.assistant.provider.embedded.ModelDownloader]
 * does for LLMs, because the whisper repo only ships these flavours and
 * the user doesn't benefit from fifty similar listings.
 *
 * URLs resolve directly off `ggerganov/whisper.cpp` on HuggingFace (the
 * same upstream the JNI bindings were built against). Quantized variants
 * are preferred: q5_1 is ~60% the size of f16 with negligible accuracy
 * loss on speech.
 */
data class WhisperModel(
    val id: String,
    val displayName: String,
    val url: String,
    val filename: String,
    val sizeMb: Int,
    val multilingual: Boolean
)

object WhisperModelCatalog {

    private const val BASE =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val TINY_Q5_1 = WhisperModel(
        id = "tiny-q5_1",
        displayName = "Whisper tiny (multilingual, ~31 MB)",
        url = "$BASE/ggml-tiny-q5_1.bin",
        filename = "ggml-tiny.bin",
        sizeMb = 31,
        multilingual = true
    )

    val BASE_Q5_1 = WhisperModel(
        id = "base-q5_1",
        displayName = "Whisper base (multilingual, ~58 MB)",
        url = "$BASE/ggml-base-q5_1.bin",
        filename = "ggml-base.bin",
        sizeMb = 58,
        multilingual = true
    )

    val SMALL_Q5_1 = WhisperModel(
        id = "small-q5_1",
        displayName = "Whisper small (multilingual, ~190 MB)",
        url = "$BASE/ggml-small-q5_1.bin",
        filename = "ggml-small.bin",
        sizeMb = 190,
        multilingual = true
    )

    /** Ordered by size ascending — the Settings UI should default to the smallest. */
    val all: List<WhisperModel> = listOf(TINY_Q5_1, BASE_Q5_1, SMALL_Q5_1)

    /** Default pick for first-run: smallest multilingual model that still transcribes Japanese reasonably. */
    val default: WhisperModel = TINY_Q5_1
}
