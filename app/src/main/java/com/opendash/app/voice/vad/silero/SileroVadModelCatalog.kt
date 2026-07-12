package com.opendash.app.voice.vad.silero

/**
 * Silero VAD's 16kHz-only ONNX model (opset 15) — verified by actually
 * downloading it and inspecting its input/output tensor shapes with
 * `onnxruntime` in Python during implementation, not guessed. URL/sha256
 * point at the real upstream file as of the snakers4/silero-vad `master`
 * branch at implementation time.
 */
object SileroVadModelCatalog {
    const val URL =
        "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_16k_op15.onnx"
    const val FILENAME = "silero_vad_16k_op15.onnx"
    const val SHA256 = "7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49"
    const val SIZE_BYTES = 1_289_603L
}
