package com.opendash.app.tool.info

/** Search index boundary so the store remains testable without Android SQLite. */
interface OfflineKnowledgeIndex {
    suspend fun search(query: String, limit: Int): List<KnowledgeEntry>
}
