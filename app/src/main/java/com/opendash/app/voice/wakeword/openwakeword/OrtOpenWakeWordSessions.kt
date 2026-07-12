package com.opendash.app.voice.wakeword.openwakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Production [OpenWakeWordSessions] backed by real ONNX Runtime
 * sessions loaded from the downloaded model files. Tensor shapes
 * verified against the real v0.5.1 release files during
 * implementation (see [OpenWakeWordModelCatalog], [OpenWakeWordSessions]).
 */
class OrtOpenWakeWordSessions(
    melspecFile: File,
    embeddingFile: File,
    classifierFile: File
) : OpenWakeWordSessions, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val melspecSession = env.createSession(melspecFile.absolutePath)
    private val embeddingSession = env.createSession(embeddingFile.absolutePath)
    private val classifierSession = env.createSession(classifierFile.absolutePath)

    override val classifierWindowSize: Int =
        classifierSession.inputInfo.values.first().info
            .let { it as ai.onnxruntime.TensorInfo }
            .shape[1].toInt()

    override fun melspectrogram(samples: FloatArray): List<FloatArray> {
        val inputName = melspecSession.inputNames.first()
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())
        ).use { input ->
            melspecSession.run(mapOf(inputName to input)).use { result ->
                // Output shape (time, 1, ?, 32) per the real model — squeeze
                // the two singleton-ish middle dims and read frames x 32 bins.
                val raw = result[0].value
                return flattenMelspecOutput(raw)
            }
        }
    }

    override fun embedding(melspecWindow: List<FloatArray>): FloatArray {
        val inputName = embeddingSession.inputNames.first()
        val flat = FloatArray(melspecWindow.size * OpenWakeWordSessions.MELSPEC_BINS)
        melspecWindow.forEachIndexed { i, frame ->
            frame.copyInto(flat, i * OpenWakeWordSessions.MELSPEC_BINS)
        }
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flat),
            longArrayOf(1, melspecWindow.size.toLong(), OpenWakeWordSessions.MELSPEC_BINS.toLong(), 1)
        ).use { input ->
            embeddingSession.run(mapOf(inputName to input)).use { result ->
                // Output shape (1, 1, 1, 96) — flatten to the 96-dim vector.
                return flattenToFloatArray(result[0].value, OpenWakeWordSessions.EMBEDDING_DIM)
            }
        }
    }

    override fun classify(embeddingWindow: List<FloatArray>): Float {
        val inputName = classifierSession.inputNames.first()
        val flat = FloatArray(embeddingWindow.size * OpenWakeWordSessions.EMBEDDING_DIM)
        embeddingWindow.forEachIndexed { i, vec ->
            vec.copyInto(flat, i * OpenWakeWordSessions.EMBEDDING_DIM)
        }
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flat),
            longArrayOf(1, embeddingWindow.size.toLong(), OpenWakeWordSessions.EMBEDDING_DIM.toLong())
        ).use { input ->
            classifierSession.run(mapOf(inputName to input)).use { result ->
                return flattenToFloatArray(result[0].value, 1).first()
            }
        }
    }

    override fun close() {
        runCatching { melspecSession.close() }
        runCatching { embeddingSession.close() }
        runCatching { classifierSession.close() }
    }

    /** Recursively flattens a nested float array/value into frames of [MELSPEC_BINS] floats. */
    private fun flattenMelspecOutput(raw: Any?): List<FloatArray> {
        val flat = mutableListOf<Float>()
        collectFloats(raw, flat)
        val bins = OpenWakeWordSessions.MELSPEC_BINS
        val frameCount = flat.size / bins
        return (0 until frameCount).map { i ->
            FloatArray(bins) { j -> flat[i * bins + j] }
        }
    }

    private fun flattenToFloatArray(raw: Any?, expectedSize: Int): FloatArray {
        val flat = mutableListOf<Float>()
        collectFloats(raw, flat)
        require(flat.size >= expectedSize) {
            "Expected at least $expectedSize floats in ONNX output, got ${flat.size}"
        }
        return FloatArray(expectedSize) { flat[it] }
    }

    private fun collectFloats(value: Any?, out: MutableList<Float>) {
        when (value) {
            is FloatArray -> value.forEach { out.add(it) }
            is Array<*> -> value.forEach { collectFloats(it, out) }
            is Float -> out.add(value)
            null -> Unit
            else -> throw IllegalArgumentException("Unexpected ONNX output element type: ${value::class}")
        }
    }

    companion object {
        fun create(
            melspecFile: File,
            embeddingFile: File,
            classifierFile: File
        ): OrtOpenWakeWordSessions? = try {
            OrtOpenWakeWordSessions(melspecFile, embeddingFile, classifierFile)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to load openWakeWord ONNX sessions")
            null
        }
    }
}
