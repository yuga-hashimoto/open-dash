package com.opendash.app.tool.info

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BraveSearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var securePreferences: SecurePreferences
    private val moshi: Moshi = Moshi.Builder().build()

    @BeforeEach
    fun setup() {
        server = MockWebServer().apply { start() }
        securePreferences = mockk(relaxed = true)
        every {
            securePreferences.getString(SecurePreferences.KEY_BRAVE_SEARCH_API_KEY)
        } returns "test-key"
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun newProvider(): BraveSearchProvider = BraveSearchProvider(
        client = OkHttpClient(),
        moshi = moshi,
        securePreferences = securePreferences,
        endpoint = server.url("/res/v1/web/search").toString(),
    )

    @Test
    fun `parses top result into abstract and source_url`() = runTest {
        val body = """
            {"web":{"results":[
              {"title":"日経平均株価","url":"https://example.com/nikkei","description":"40000円台で推移"},
              {"title":"NYダウ","url":"https://example.com/dow","description":"前日比+200ドル"}
            ]}}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = newProvider().search("日経平均")

        assertThat(result.query).isEqualTo("日経平均")
        assertThat(result.abstract).contains("日経平均株価")
        assertThat(result.abstract).contains("40000円台で推移")
        assertThat(result.sourceUrl).isEqualTo("https://example.com/nikkei")
        assertThat(result.relatedTopics).hasSize(1)
        assertThat(result.relatedTopics[0]).contains("NYダウ")
    }

    @Test
    fun `sends X-Subscription-Token header with api key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"web":{"results":[]}}"""))

        newProvider().search("hello")

        val request = server.takeRequest()
        assertThat(request.getHeader("X-Subscription-Token")).isEqualTo("test-key")
        assertThat(request.path).contains("q=hello")
    }

    @Test
    fun `throws when api key is unset so chain falls through`() = runTest {
        every {
            securePreferences.getString(SecurePreferences.KEY_BRAVE_SEARCH_API_KEY)
        } returns ""

        assertThrows<IllegalStateException> {
            newProvider().search("anything")
        }
    }

    @Test
    fun `empty results return blank SearchResult instead of throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"web":{"results":[]}}"""))

        val result = newProvider().search("zerohits")

        assertThat(result.abstract).isEmpty()
        assertThat(result.sourceUrl).isNull()
        assertThat(result.relatedTopics).isEmpty()
    }

    @Test
    fun `non-200 throws so chain advances to fallback`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertThrows<RuntimeException> {
            newProvider().search("ratelimited")
        }
    }
}
