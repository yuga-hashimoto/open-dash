package com.opendash.app.voice.vad.silero

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Production [SileroVadSession] backed by a real ONNX Runtime session loaded from the downloaded model file. */
class OrtSileroVadSession(modelFile: File) : SileroVadSession, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.absolutePath)
    private val inputName = "input"
    private val stateName = "state"
    private val srName = "sr"

    override fun infer(inputSamples: FloatArray, state: FloatArray): SileroVadSession.Result {
        require(inputSamples.size == SileroVadSession.CHUNK_SAMPLES + SileroVadSession.CONTEXT_SAMPLES) {
            "inputSamples must be ${SileroVadSession.CHUNK_SAMPLES + SileroVadSession.CONTEXT_SAMPLES} long"
        }
        require(state.size == SileroVadSession.STATE_SIZE) {
            "state must be ${SileroVadSession.STATE_SIZE} long"
        }

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(inputSamples), longArrayOf(1, inputSamples.size.toLong())
        ).use { inputTensor ->
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)
            ).use { stateTensor ->
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(SileroVadSession.SAMPLE_RATE)), longArrayOf()
                ).use { srTensor ->
                    session.run(
                        mapOf(inputName to inputTensor, stateName to stateTensor, srName to srTensor)
                    ).use { result ->
                        val prob = flattenToFloatArray(result[0].value, 1).first()
                        val nextState = flattenToFloatArray(result[1].value, SileroVadSession.STATE_SIZE)
                        return SileroVadSession.Result(prob, nextState)
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    private fun flattenToFloatArray(raw: Any?, expectedSize: Int): FloatArray {
        val flat = mutableListOf<Float>()
        collectFloats(raw, flat)
        require(flat.size >= expectedSize) { "Expected at least $expectedSize floats in ONNX output, got ${flat.size}" }
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
        fun create(modelFile: File): OrtSileroVadSession? = try {
            OrtSileroVadSession(modelFile)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to load Silero VAD ONNX session")
            null
        }
    }
}
