package com.opendash.app.tool.info

import com.opendash.app.data.db.KnowledgeDao
import com.opendash.app.data.db.KnowledgeEntity
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import timber.log.Timber
import java.util.UUID

/**
 * Room-backed [KnowledgeStore]. Loads candidate rows from [KnowledgeDao],
 * then scores query terms in Kotlin using the same semantics as
 * [InMemoryKnowledgeStore].
 */
class RoomKnowledgeStore(
    private val dao: KnowledgeDao,
    moshi: Moshi
) : KnowledgeStore {

    private val tagsAdapter: JsonAdapter<List<String>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    override suspend fun search(query: String, limit: Int): List<KnowledgeEntry> {
        val terms = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        return dao.listAll()
            .map { it.toDomain() }
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    override suspend fun add(entry: KnowledgeEntry) {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        dao.upsert(
            KnowledgeEntity(
                id = id,
                question = entry.question,
                answer = entry.answer,
                tagsJson = tagsAdapter.toJson(entry.tags),
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun remove(id: String): Boolean = dao.delete(id) > 0

    override suspend fun listAll(): List<KnowledgeEntry> =
        dao.listAll().map { it.toDomain() }

    private fun KnowledgeEntity.toDomain(): KnowledgeEntry {
        val tags = try {
            tagsAdapter.fromJson(tagsJson) ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse knowledge tags for $id")
            emptyList()
        }
        return KnowledgeEntry(
            id = id,
            question = question,
            answer = answer,
            tags = tags
        )
    }

    private fun score(entry: KnowledgeEntry, terms: List<String>): Int {
        val haystack = buildString {
            append(entry.question.lowercase())
            append(' ')
            append(entry.answer.lowercase())
            append(' ')
            append(entry.tags.joinToString(" ") { it.lowercase() })
        }
        return terms.count { term -> haystack.contains(term) }
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")
    }
}
