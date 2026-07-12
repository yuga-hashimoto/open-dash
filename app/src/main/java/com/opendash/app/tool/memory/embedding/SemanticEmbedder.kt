package com.opendash.app.tool.memory.embedding

/**
 * Produces sentence embeddings for semantic search, abstracted so
 * [com.opendash.app.tool.memory.SemanticMemorySearch] is unit-testable
 * without a real MediaPipe `TextEmbedder` — same reasoning as
 * [com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordSessions].
 *
 * Retrieval-style embedding models (EmbeddingGemma included) score
 * better when the query and the stored documents are embedded with
 * slightly different prompt framing — see [Role].
 */
interface SemanticEmbedder {
    enum class Role { QUERY, DOCUMENT }

    /** Returns `null` if the embedder isn't available (model not downloaded, failed to load) or inference fails. */
    fun embed(text: String, role: Role): FloatArray?

    fun isAvailable(): Boolean
}

/** Cosine similarity between two equal-length embedding vectors. Returns 0 if either has zero norm. */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA <= 0.0 || normB <= 0.0) return 0.0
    return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
}
