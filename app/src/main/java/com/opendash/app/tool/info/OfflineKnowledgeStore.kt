package com.opendash.app.tool.info

/**
 * Merges durable user knowledge with the bundled offline starter corpus.
 * User-authored entries win ties; both paths remain available without network.
 */
class OfflineKnowledgeStore(
    private val userStore: KnowledgeStore,
    private val index: OfflineKnowledgeIndex,
    private val corpus: List<KnowledgeEntry> = OfflineKnowledgeCorpus.entries,
) : KnowledgeStore {

    override suspend fun search(query: String, limit: Int): List<KnowledgeEntry> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val user = userStore.search(query, limit = limit)
        val indexed = index.search(query, limit = limit)
        val bundled = if (indexed.isNotEmpty()) indexed else corpus.filter { score(it, query) > 0 }
        return (user.map { Ranked(it, score(it, query), userAuthored = true) } +
            bundled.map { Ranked(it, score(it, query), userAuthored = false) })
            .distinctBy { it.entry.id }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenByDescending { it.userAuthored })
            .take(limit)
            .map { it.entry }
    }

    override suspend fun add(entry: KnowledgeEntry) = userStore.add(entry)

    override suspend fun remove(id: String): Boolean = userStore.remove(id)

    override suspend fun listAll(): List<KnowledgeEntry> = userStore.listAll() + corpus

    private fun score(entry: KnowledgeEntry, query: String): Int {
        val haystack = buildString {
            append(entry.question.lowercase())
            append(' ')
            append(entry.answer.lowercase())
            append(' ')
            append(entry.tags.joinToString(" ") { it.lowercase() })
        }
        return query.lowercase().split(WHITESPACE)
            .count { it.isNotBlank() && haystack.contains(it) }
    }

    private data class Ranked(val entry: KnowledgeEntry, val score: Int, val userAuthored: Boolean)

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
