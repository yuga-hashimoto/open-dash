package com.opendash.app.assistant.provider.openai

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

class OpenAiCompatibleProviderTest {

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

    @Test
    fun `defaults preserve the embedded-mode id and display name`() {
        val provider = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = "http://example")
        )
        assertThat(provider.id).isEqualTo("openai_compatible")
        assertThat(provider.displayName).isEqualTo("Local LLM")
    }

    @Test
    fun `custom id and display name are used when supplied`() {
        val provider = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = "http://example"),
            id = "api_abc123",
            displayName = "My OpenAI Account"
        )
        assertThat(provider.id).isEqualTo("api_abc123")
        assertThat(provider.displayName).isEqualTo("My OpenAI Account")
    }

    @Test
    fun `two instances with different ids can both call their own base url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"hi from A"}}]}"""))
        val providerA = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = server.url("/").toString().trimEnd('/')),
            id = "api_a",
            displayName = "Provider A"
        )
        val session = providerA.startSession()
        val msg = providerA.send(session, listOf(AssistantMessage.User(content = "hi")), emptyList())
        assertThat((msg as AssistantMessage.Assistant).content).isEqualTo("hi from A")
        assertThat(providerA.id).isEqualTo("api_a")
    }
}
