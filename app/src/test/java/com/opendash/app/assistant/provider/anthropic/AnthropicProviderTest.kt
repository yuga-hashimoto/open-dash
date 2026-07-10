package com.opendash.app.assistant.provider.anthropic

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
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

class AnthropicProviderTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun provider(apiKey: String = "sk-ant-test") = AnthropicProvider(
        client = client,
        moshi = moshi,
        config = AnthropicConfig(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = apiKey, model = "claude-sonnet-5")
    )

    @Test
    fun `capabilities declare a non-local streaming tool-capable provider`() {
        assertThat(provider().capabilities.isLocal).isFalse()
        assertThat(provider().capabilities.supportsStreaming).isTrue()
        assertThat(provider().capabilities.supportsTools).isTrue()
    }

    @Test
    fun `send parses a non-streaming text response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"type":"text","text":"Hello there"}],"stop_reason":"end_turn"}"""
            )
        )
        val p = provider()
        val session = p.startSession()
        val msg = p.send(session, listOf(AssistantMessage.User(content = "hi")), emptyList())
        assertThat((msg as AssistantMessage.Assistant).content).isEqualTo("Hello there")
    }

    @Test
    fun `send sets x-api-key and anthropic-version headers, not Authorization`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider(apiKey = "sk-ant-secret")
        p.send(p.startSession(), listOf(AssistantMessage.User(content = "hi")), emptyList())

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-ant-secret")
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun `send posts to the v1 messages endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider()
        p.send(p.startSession(), listOf(AssistantMessage.User(content = "hi")), emptyList())

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/messages")
    }

    @Test
    fun `isAvailable returns true on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(provider().isAvailable()).isTrue()
    }

    @Test
    fun `isAvailable returns false on error status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(provider().isAvailable()).isFalse()
    }
}
