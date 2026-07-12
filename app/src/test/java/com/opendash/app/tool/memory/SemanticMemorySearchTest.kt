package com.opendash.app.tool.memory

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.MemoryDao
import com.opendash.app.data.db.MemoryEntity
import com.opendash.app.tool.memory.embedding.SemanticEmbedder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SemanticMemorySearchTest {

    private class FakeDao(private val rows: List<MemoryEntity>) : MemoryDao {
        override suspend fun upsert(memory: MemoryEntity) = Unit
        override suspend fun get(key: String): MemoryEntity? = rows.find { it.key == key }
        override suspend fun search(query: String, limit: Int) = rows.take(limit)
        override suspend fun list(limit: Int) = rows.take(limit)
        override suspend fun listByPrefix(prefix: String, limit: Int) =
            rows.filter { it.key.startsWith(prefix) }.take(limit)
        override suspend fun delete(key: String): Int = 0
        override suspend fun clear() = Unit
    }

    /** Maps exact text -> a hand-picked vector so cosine similarity is easy to reason about. */
    private class FakeEmbedder(
        private val vectors: Map<String, FloatArray>,
        private val available: Boolean = true
    ) : SemanticEmbedder {
        override fun isAvailable(): Boolean = available
        override fun embed(text: String, role: SemanticEmbedder.Role): FloatArray? = vectors[text]
    }

    private fun entry(key: String, value: String) =
        MemoryEntity(key, value, updatedAtMs = 0L)

    @Test
    fun `empty memory returns empty hits`() = runTest {
        val search = SemanticMemorySearch(FakeDao(emptyList()))
        assertThat(search.searchSemantic("anything")).isEmpty()
    }

    @Test
    fun `top hit matches the query topic`() = runTest {
        val rows = listOf(
            entry("favorite_color", "my favorite color is deep blue"),
            entry("favorite_food", "sushi is the best food"),
            entry("pet", "I have a golden retriever named Mocha")
        )
        val search = SemanticMemorySearch(FakeDao(rows))

        val hits = search.searchSemantic("blue color")

        assertThat(hits).isNotEmpty()
        assertThat(hits.first().key).isEqualTo("favorite_color")
    }

    @Test
    fun `respects limit`() = runTest {
        val rows = (1..10).map { entry("color_$it", "color value $it") }
        val search = SemanticMemorySearch(FakeDao(rows))
        val hits = search.searchSemantic("color", limit = 3)
        assertThat(hits.size).isAtMost(3)
    }

    @Test
    fun `query with no matching terms returns empty`() = runTest {
        val rows = listOf(entry("k1", "apple banana"))
        val search = SemanticMemorySearch(FakeDao(rows))
        val hits = search.searchSemantic("zzzzz-nothing-matches")
        assertThat(hits).isEmpty()
    }

    @Test
    fun `uses the embedder and ranks by cosine similarity when available`() = runTest {
        val rows = listOf(
            entry("favorite_color", "my favorite color is deep blue"),
            entry("favorite_food", "sushi is the best food")
        )
        val embedder = FakeEmbedder(
            mapOf(
                "blue color" to floatArrayOf(1f, 0f),
                "favorite_color my favorite color is deep blue" to floatArrayOf(0.9f, 0.1f),
                "favorite_food sushi is the best food" to floatArrayOf(0f, 1f)
            )
        )
        val search = SemanticMemorySearch(FakeDao(rows)) { embedder }

        val hits = search.searchSemantic("blue color")

        assertThat(hits.first().key).isEqualTo("favorite_color")
    }

    @Test
    fun `falls back to TF-IDF when the embedder is unavailable`() = runTest {
        val rows = listOf(
            entry("favorite_color", "my favorite color is deep blue"),
            entry("favorite_food", "sushi is the best food")
        )
        val embedder = FakeEmbedder(vectors = emptyMap(), available = false)
        val search = SemanticMemorySearch(FakeDao(rows)) { embedder }

        val hits = search.searchSemantic("blue color")

        assertThat(hits.first().key).isEqualTo("favorite_color")
    }

    @Test
    fun `falls back to TF-IDF when the embedder fails to embed the query`() = runTest {
        val rows = listOf(entry("favorite_color", "my favorite color is deep blue"))
        // Available, but embed() returns null for every input (e.g. inference threw).
        val embedder = FakeEmbedder(vectors = emptyMap(), available = true)
        val search = SemanticMemorySearch(FakeDao(rows)) { embedder }

        val hits = search.searchSemantic("blue color")

        assertThat(hits.first().key).isEqualTo("favorite_color")
    }
}
