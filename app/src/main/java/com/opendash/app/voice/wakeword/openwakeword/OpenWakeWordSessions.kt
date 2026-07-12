package com.opendash.app.voice.wakeword.openwakeword

/**
 * Wraps the three ONNX Runtime sessions openWakeWord's pipeline needs,
 * abstracted so [OpenWakeWordFeatureExtractor] is unit-testable without
 * a real ONNX Runtime session — same reasoning as [AlarmPlayer][com.opendash.app.tool.system.AlarmPlayer]
 * abstracting [android.media.MediaPlayer].
 *
 * Exact shapes verified against the real v0.5.1 release model files
 * (see [OpenWakeWordModelCatalog]):
 *  - melspectrogram.onnx: input `(1, samples)` float32 → output squeezed to `(frames, 32)`.
 *  - embedding_model.onnx: input `(1, 76, 32, 1)` float32 → output squeezed to a 96-dim vector.
 *  - classifier (e.g. hey_jarvis_v0.1.onnx): input `(1, classifierWindowSize, 96)` → single sigmoid score.
 */
interface OpenWakeWordSessions {
    /** Raw audio samples (int16 range, as float32) → mel-spectrogram frames, each [MELSPEC_BINS] wide. */
    fun melspectrogram(samples: FloatArray): List<FloatArray>

    /** A window of exactly [EMBEDDING_WINDOW_FRAMES] mel-spectrogram frames → one [EMBEDDING_DIM]-wide embedding. */
    fun embedding(melspecWindow: List<FloatArray>): FloatArray

    /** A window of [classifierWindowSize] embeddings → a single score in `[0, 1]`. */
    fun classify(embeddingWindow: List<FloatArray>): Float

    /** Queried from the classifier model's own input shape, mirroring the Python reference's `model_inputs[mdl]`. */
    val classifierWindowSize: Int

    companion object {
        const val FRAME_SAMPLES = 1280 // 80ms @ 16kHz
        const val MELSPEC_BINS = 32
        const val EMBEDDING_WINDOW_FRAMES = 76
        const val EMBEDDING_DIM = 96
    }
}
