package com.opendash.app.tool.info

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OfflineKnowledgeStoreTest {
    private val user = InMemoryKnowledgeStore()
    private val corpus = listOf(
        KnowledgeEntry("builtin-a", "What is a comet?", "A comet is an icy body.", listOf("space")),
        KnowledgeEntry("builtin-b", "What is HTTP?", "HTTP transfers web resources.", listOf("web"))
    )
    private val index = object : OfflineKnowledgeIndex {
        override suspend fun search(query: String, limit: Int): List<KnowledgeEntry> =
            corpus.filter { it.answer.contains("HTTP", ignoreCase = true) }.take(limit)
    }

    @Test
    fun `bundled corpus is searchable without user rows`() = runTest {
        val result = OfflineKnowledgeStore(user, index, corpus).search("HTTP", 3)
        assertThat(result.map { it.id }).containsExactly("builtin-b").inOrder()
    }

    @Test
    fun `user-authored entry wins equal relevance`() = runTest {
        user.add(KnowledgeEntry("user-http", "What is HTTP?", "My local HTTP note.", listOf("web")))
        val result = OfflineKnowledgeStore(user, index, corpus).search("HTTP", 2)
        assertThat(result.first().id).isEqualTo("user-http")
    }

    @Test
    fun `limit and empty query are enforced`() = runTest {
        val store = OfflineKnowledgeStore(user, index, corpus)
        assertThat(store.search("HTTP", 1)).hasSize(1)
        assertThat(store.search("", 3)).isEmpty()
    }

    @Test
    fun `add and remove delegate to durable store`() = runTest {
        val store = OfflineKnowledgeStore(user, index, corpus)
        store.add(KnowledgeEntry("user-1", "A local fact", "Answer", emptyList()))
        assertThat(store.listAll().map { it.id }).contains("user-1")
        assertThat(store.remove("user-1")).isTrue()
        assertThat(store.listAll().map { it.id }).doesNotContain("user-1")
    }
}
