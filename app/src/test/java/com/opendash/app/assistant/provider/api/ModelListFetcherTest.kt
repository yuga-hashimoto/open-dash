package com.opendash.app.assistant.provider.api

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ModelListFetcherTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private lateinit var fetcher: ModelListFetcher

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        fetcher = ModelListFetcher(client, moshi)
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `fetch parses model ids from the data array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"gpt-5.5"},{"id":"gpt-5.5-mini"}]}"""
            )
        )
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).containsExactly("gpt-5.5", "gpt-5.5-mini")
    }

    @Test
    fun `fetch sends bearer auth header for bearer style`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
    }

    @Test
    fun `fetch sends x-api-key and anthropic-version for anthropic style`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-ant-test", "anthropic")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-ant-test")
        assertThat(recorded.getHeader("anthropic-version")).isNotNull()
    }

    @Test
    fun `fetch returns failure on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `fetch returns failure on unparseable body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isFailure).isTrue()
    }
}
