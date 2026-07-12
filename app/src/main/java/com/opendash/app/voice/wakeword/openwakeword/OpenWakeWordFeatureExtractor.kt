package com.opendash.app.voice.wakeword.openwakeword

import com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordSessions.Companion.EMBEDDING_WINDOW_FRAMES
import com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordSessions.Companion.FRAME_SAMPLES

/**
 * Streaming feature-extraction pipeline for openWakeWord, ported from
 * the upstream Python reference (`openwakeword/utils.py`'s
 * `AudioFeatures._streaming_features` / `_streaming_melspectrogram` /
 * `get_features`, `openwakeword/model.py`'s `predict`) as of the
 * v0.5.1 release. Verified against the real downloaded ONNX model
 * files' input/output tensor shapes during implementation, not
 * guessed — see [OpenWakeWordSessions]'s KDoc.
 *
 * Ported constants (all confirmed against the real models):
 *  - Melspectrogram context window: current 80ms chunk (1280 samples)
 *    plus 480 samples (`160*3`) of lookback from prior chunks, so
 *    consecutive calls produce exactly 8 new, non-overlapping
 *    mel-spectrogram frames each — this is how the upstream streaming
 *    implementation avoids re-deriving already-seen frames.
 *  - One new embedding is extracted per 80ms chunk, from the trailing
 *    76-frame mel-spectrogram window (`EMBEDDING_WINDOW_FRAMES`).
 *  - The classifier consumes the trailing `classifierWindowSize`
 *    embeddings (16 for hey_jarvis_v0.1.onnx — queried from the model
 *    itself via [OpenWakeWordSessions.classifierWindowSize], not
 *    hardcoded, matching the Python reference's own
 *    `model_inputs[mdl]` approach).
 *  - The first [WARMUP_PREDICTIONS] real predictions are forced to
 *    `0f` regardless of the model's raw score, matching upstream's
 *    "zero predictions for first 5 frames during model initialization"
 *    guard against spurious early triggers.
 *
 * Caller contract: [processChunk] must be called with **exactly**
 * [FRAME_SAMPLES] (1280) samples per call — this mirrors how real-time
 * audio capture already reads fixed-size chunks elsewhere in this
 * codebase (see `VoskWakeWordDetector`, `AudioRecordPcmSource`) and
 * sidesteps the upstream implementation's more general (and more
 * error-prone to port) handling of arbitrary chunk sizes / multiple
 * 80ms groups arriving in a single call.
 *
 * Deliberate divergence from upstream, found and verified by running
 * the actual Python reference (`pip install openwakeword`) against
 * synthetic audio during implementation: upstream pre-seeds
 * `melspectrogram_buffer` with 76 placeholder "ones" frames and
 * `feature_buffer` with ~41 embeddings computed from 4 seconds of
 * random noise at `AudioFeatures.__init__` time, so the classifier
 * always has a full window from the very first streaming call and
 * relies entirely on the "zero the first 5 real predictions" guard to
 * suppress nonsense-buffer output. This port skips that seeding and
 * simply returns `null` until real audio has filled each buffer
 * (~2.5s of cold-start latency instead of upstream's near-instant
 * start) — simpler, and it never produces a score derived from
 * placeholder/random data, at the cost of a slower first possible
 * detection after (re)starting to listen.
 *
 * NOT YET VALIDATED against real audio or a real device — see
 * docs/roadmap.md's P21 entry for this feature. The buffering
 * arithmetic was cross-checked against the real upstream Python
 * package's `AudioFeatures` (both use the real downloaded ONNX model
 * files) and produces matching mel-spectrogram/embedding buffer
 * growth, but only a real openWakeWord-vs-this-port comparison on
 * live "hey jarvis" audio on-device can confirm true detection
 * accuracy — the upstream README itself notes the streaming path has
 * "slight numerical issues" versus whole-clip computation.
 */
class OpenWakeWordFeatureExtractor(
    private val sessions: OpenWakeWordSessions
) {

    private val rawSampleBuffer = ArrayDeque<Float>()
    private val melspecBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()
    private var predictionCount = 0

    /**
     * Feeds one 80ms (1280-sample) chunk of 16kHz PCM audio (as
     * float32, matching [OpenWakeWordSessions]). Returns a
     * wake-word confidence score in `[0, 1]` once enough history has
     * accumulated to run the classifier, or `null` while still
     * warming up the mel-spectrogram/embedding buffers.
     */
    fun processChunk(samples: FloatArray): Float? {
        require(samples.size == FRAME_SAMPLES) {
            "processChunk requires exactly $FRAME_SAMPLES samples, got ${samples.size}"
        }

        rawSampleBuffer.addAll(samples.toList())
        while (rawSampleBuffer.size > RAW_BUFFER_MAX_LEN) rawSampleBuffer.removeFirst()

        val contextSize = minOf(rawSampleBuffer.size, FRAME_SAMPLES + MELSPEC_LOOKBACK_SAMPLES)
        val context = rawSampleBuffer.toList().takeLast(contextSize).toFloatArray()

        val newFrames = sessions.melspectrogram(context).map { frame -> melspecTransform(frame) }
        melspecBuffer.addAll(newFrames)
        while (melspecBuffer.size > MELSPEC_BUFFER_MAX_LEN) melspecBuffer.removeFirst()

        if (melspecBuffer.size < EMBEDDING_WINDOW_FRAMES) return null

        val embeddingWindow = melspecBuffer.toList().takeLast(EMBEDDING_WINDOW_FRAMES)
        val embedding = sessions.embedding(embeddingWindow)
        featureBuffer.addLast(embedding)
        while (featureBuffer.size > FEATURE_BUFFER_MAX_LEN) featureBuffer.removeFirst()

        val windowSize = sessions.classifierWindowSize
        if (featureBuffer.size < windowSize) return null

        val classifierWindow = featureBuffer.toList().takeLast(windowSize)
        val score = sessions.classify(classifierWindow)
        predictionCount++
        return if (predictionCount <= WARMUP_PREDICTIONS) 0f else score
    }

    /** Clears all buffers, e.g. after a detection fires and listening restarts fresh. */
    fun reset() {
        rawSampleBuffer.clear()
        melspecBuffer.clear()
        featureBuffer.clear()
        predictionCount = 0
    }

    /** `x/10 + 2` — matches the reference's transform to align the ONNX melspectrogram output with the original TF implementation. */
    private fun melspecTransform(frame: FloatArray): FloatArray =
        FloatArray(frame.size) { i -> frame[i] / 10f + 2f }

    companion object {
        /** `160 * 3` in the Python reference — extra raw-sample context so consecutive calls yield non-overlapping new frames. */
        private const val MELSPEC_LOOKBACK_SAMPLES = 480
        /** `sr * 10` in the Python reference (10 seconds of 16kHz audio). */
        private const val RAW_BUFFER_MAX_LEN = 160_000
        /** `10 * 97` in the Python reference (~10 seconds of mel-spectrogram frames at ~97 frames/sec). */
        private const val MELSPEC_BUFFER_MAX_LEN = 970
        /** `feature_buffer_max_len` in the Python reference (~10 seconds of embedding history). */
        private const val FEATURE_BUFFER_MAX_LEN = 120
        private const val WARMUP_PREDICTIONS = 5
    }
}
