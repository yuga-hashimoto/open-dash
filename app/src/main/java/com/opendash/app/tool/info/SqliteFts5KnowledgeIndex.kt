package com.opendash.app.tool.info

import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

/**
 * SQLite FTS5 index for the bundled corpus.
 *
 * Android builds differ in SQLite extensions. If FTS5 is unavailable, this
 * index reports an empty result and the store's deterministic Kotlin fallback
 * remains authoritative; it never claims that indexing succeeded.
 */
class SqliteFts5KnowledgeIndex(
    private val database: SupportSQLiteDatabase,
    private val corpus: List<KnowledgeEntry> = OfflineKnowledgeCorpus.entries,
) : OfflineKnowledgeIndex {

    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var available = false

    override suspend fun search(query: String, limit: Int): List<KnowledgeEntry> {
        ensureInitialized()
        if (!available || query.isBlank() || limit <= 0) return emptyList()

        val safeQuery = query.trim()
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .joinToString(" AND ") { "\"${it.replace("\"", "") }\"" }
        if (safeQuery.isBlank()) return emptyList()

        return runCatching {
            val rows = mutableListOf<KnowledgeEntry>()
            database.query(
                "SELECT id, question, answer, tags FROM $TABLE_NAME " +
                    "WHERE $TABLE_NAME MATCH ? ORDER BY bm25($TABLE_NAME) LIMIT ?",
                arrayOf<Any?>(safeQuery, limit)
            ).use { cursor ->
                val id = cursor.getColumnIndexOrThrow("id")
                val question = cursor.getColumnIndexOrThrow("question")
                val answer = cursor.getColumnIndexOrThrow("answer")
                val tags = cursor.getColumnIndexOrThrow("tags")
                while (cursor.moveToNext()) {
                    rows += KnowledgeEntry(
                        id = cursor.getString(id),
                        question = cursor.getString(question),
                        answer = cursor.getString(answer),
                        tags = cursor.getString(tags).split(',').filter { it.isNotBlank() }
                    )
                }
            }
            rows
        }.getOrElse {
            Timber.w(it, "SQLite FTS5 query unavailable; using Kotlin knowledge fallback")
            available = false
            emptyList()
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            runCatching {
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_NAME USING fts5(" +
                        "id UNINDEXED, question, answer, tags, tokenize='unicode61')"
                )
                database.execSQL("DELETE FROM $TABLE_NAME")
                corpus.forEach { entry ->
                    database.execSQL(
                        "INSERT INTO $TABLE_NAME(id, question, answer, tags) VALUES (?, ?, ?, ?)",
                        arrayOf<Any?>(entry.id, entry.question, entry.answer, entry.tags.joinToString(","))
                    )
                }
                available = true
            }.onFailure {
                available = false
                Timber.w(it, "SQLite FTS5 unavailable; bundled knowledge will use Kotlin search")
            }
        }
    }

    companion object {
        private const val TABLE_NAME = "offline_knowledge_fts"
        private val WHITESPACE = Regex("\\s+")
    }
}
