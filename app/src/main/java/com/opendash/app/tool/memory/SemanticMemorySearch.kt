package com.opendash.app.tool.memory

import com.opendash.app.data.db.MemoryDao
import com.opendash.app.data.db.MemoryEntity
import com.opendash.app.tool.memory.embedding.SemanticEmbedder
import com.opendash.app.tool.memory.embedding.cosineSimilarity

/**
 * Rebuilds an index from memory rows on each call. For small corpora
 * (~hundreds of entries) this is fine; for larger datasets we'd cache
 * + invalidate on upsert/delete.
 *
 * Uses a [SemanticEmbedder] (MediaPipe EmbeddingGemma, P16.4) when one
 * becomes available — genuine semantic matching across EN/JA rather
 * than term-overlap — falling back to [TfIdfIndex] when no embedder
 * has been provided, its model isn't downloaded yet, or embedding the
 * query/every document fails.
 *
 * [embedderProvider] is a factory, not a fixed value, so a model the
 * user downloads mid-session activates on the next search without an
 * app restart — same "re-check readiness per use, don't require a
 * restart" convention as `WhisperSttProvider`'s active-model
 * resolution. The constructed instance is cached (loading a ~180MB
 * model is expensive) and only rebuilt if it ever reports itself
 * unavailable.
 */
class SemanticMemorySearch(
    private val dao: MemoryDao,
    private val embedderProvider: () -> SemanticEmbedder? = { null }
) {

    private var cachedEmbedder: SemanticEmbedder? = null

    suspend fun searchSemantic(query: String, limit: Int = 5): List<MemoryEntity> {
        val all = dao.list(limit = 500)
        if (all.isEmpty()) return emptyList()

        resolveEmbedder()?.let { e ->
            searchWithEmbeddings(query, all, limit, e)?.let { return it }
        }
        return searchWithTfIdf(query, all, limit)
    }

    private fun resolveEmbedder(): SemanticEmbedder? {
        cachedEmbedder?.let { if (it.isAvailable()) return it }
        val fresh = embedderProvider()?.takeIf { it.isAvailable() } ?: return null
        cachedEmbedder = fresh
        return fresh
    }

    /** Returns `null` (rather than an empty list) to signal "embeddings unusable, fall back" vs. "no matches". */
    private fun searchWithEmbeddings(
        query: String,
        all: List<MemoryEntity>,
        limit: Int,
        e: SemanticEmbedder
    ): List<MemoryEntity>? {
        val queryVector = e.embed(query, SemanticEmbedder.Role.QUERY) ?: return null

        val scored = all.mapNotNull { entry ->
            val docVector = e.embed("${entry.key} ${entry.value}", SemanticEmbedder.Role.DOCUMENT)
                ?: return@mapNotNull null
            entry to cosineSimilarity(queryVector, docVector)
        }
        if (scored.isEmpty()) return null

        return scored
            .filter { (_, score) -> score > MIN_COSINE_SIMILARITY }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, _) -> entry }
    }

    private fun searchWithTfIdf(query: String, all: List<MemoryEntity>, limit: Int): List<MemoryEntity> {
        val docs = all.map { e ->
            TfIdfIndex.Document(
                id = e.key,
                text = "${e.key} ${e.value}"
            )
        }
        val index = TfIdfIndex(docs)
        val hits = index.search(query, limit)
        val byId = all.associateBy { it.key }
        return hits.mapNotNull { byId[it.document.id] }
    }

    private companion object {
        /** Below this, a cosine-similarity "hit" is noise, not a real match. */
        const val MIN_COSINE_SIMILARITY = 0.3
    }
}
