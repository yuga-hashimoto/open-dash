package com.opendash.app.tool.info

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.KnowledgeDao
import com.opendash.app.data.db.KnowledgeEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomKnowledgeStoreTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private lateinit var dao: FakeKnowledgeDao

    @BeforeEach
    fun setup() {
        dao = FakeKnowledgeDao()
    }

    private fun store() = RoomKnowledgeStore(dao, moshi)

    @Test
    fun `add assigns id when blank`() = runTest {
        val s = store()
        s.add(KnowledgeEntry(id = "", question = "What", answer = "A"))
        val all = s.listAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].id).isNotEmpty()
        assertThat(all[0].question).isEqualTo("What")
        assertThat(all[0].answer).isEqualTo("A")
    }

    @Test
    fun `add preserves explicit id`() = runTest {
        val s = store()
        s.add(KnowledgeEntry(id = "fixed-id", question = "Q", answer = "A"))
        assertThat(s.listAll()[0].id).isEqualTo("fixed-id")
    }

    @Test
    fun `search matches and ranks question answer and tags`() = runTest {
        val s = store()
        s.add(
            KnowledgeEntry(
                "high",
                "coffee beans roasting guide",
                "Dark roast for espresso.",
                listOf("coffee", "roasting")
            )
        )
        s.add(
            KnowledgeEntry("low", "tea basics", "Boil water.", listOf("tea"))
        )
        s.add(
            KnowledgeEntry(
                "mid",
                "How do I brew coffee?",
                "Use beans and hot water.",
                listOf("drink")
            )
        )

        val hits = s.search("coffee roasting beans")
        assertThat(hits.map { it.id }.first()).isEqualTo("high")
        assertThat(hits.map { it.id }).contains("mid")
        assertThat(hits.map { it.id }).doesNotContain("low")
    }

    @Test
    fun `search is case insensitive and ignores blank query`() = runTest {
        val s = store()
        s.add(KnowledgeEntry("1", "Tesla charging", "CCS plug", listOf("EV")))
        assertThat(s.search("TESLA").map { it.id }).containsExactly("1")
        assertThat(s.search("   ")).isEmpty()
    }

    @Test
    fun `remove is durable across store instances sharing dao`() = runTest {
        val first = store()
        first.add(KnowledgeEntry("id-x", "q", "a"))
        assertThat(first.remove("id-x")).isTrue()
        assertThat(first.remove("missing")).isFalse()

        val second = store()
        assertThat(second.listAll()).isEmpty()
        assertThat(second.search("q")).isEmpty()
    }

    @Test
    fun `second store instance reads same fake dao rows`() = runTest {
        val first = store()
        first.add(
            KnowledgeEntry(
                "shared",
                "wifi password",
                "hunter2",
                listOf("network", "home")
            )
        )

        val second = store()
        val all = second.listAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].id).isEqualTo("shared")
        assertThat(all[0].question).isEqualTo("wifi password")
        assertThat(all[0].answer).isEqualTo("hunter2")
        assertThat(all[0].tags).containsExactly("network", "home")
        assertThat(second.search("wifi").map { it.id }).containsExactly("shared")
    }
}

/** In-memory KnowledgeDao for unit tests (no Room Android runtime). */
private class FakeKnowledgeDao : KnowledgeDao {
    private val rows = linkedMapOf<String, KnowledgeEntity>()

    override suspend fun upsert(entry: KnowledgeEntity) {
        rows[entry.id] = entry
    }

    override suspend fun get(id: String): KnowledgeEntity? = rows[id]

    override suspend fun listAll(): List<KnowledgeEntity> = rows.values.toList()

    override suspend fun delete(id: String): Int =
        if (rows.remove(id) != null) 1 else 0
}
