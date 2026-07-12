package com.opendash.app.tool.memory.embedding

/**
 * Google's official MediaPipe-packaged EmbeddingGemma model (int4/int8
 * quantized `.task` bundle) — multilingual (100+ languages including
 * Japanese), and its tokenizer is built into the bundle so no
 * hand-rolled SentencePiece implementation is needed (see P16.4's
 * roadmap entry for why that was the deciding factor over a raw
 * sentence-transformers ONNX export).
 *
 * URL/sha256/size verified by actually downloading the file and
 * hashing it during implementation, not guessed.
 */
object EmbeddingGemmaModelCatalog {
    const val URL =
        "https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task"
    const val FILENAME = "embedding_gemma.task"
    const val SHA256 = "913b7a1edc7c7c3d1da3979ec1d0648ed9e0a370f181bb59ab177ca4b97707ad"
    const val SIZE_BYTES = 183_816_181L
}
