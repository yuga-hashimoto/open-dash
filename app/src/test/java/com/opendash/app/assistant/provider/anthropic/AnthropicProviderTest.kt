package com.opendash.app.assistant.provider.anthropic

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolSchema
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.toList
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

    @Suppress("UNCHECKED_CAST")
    private fun requestBodyAsMap(): Map<String, Any?> {
        val json = server.takeRequest().body.readUtf8()
        return moshi.adapter(Map::class.java).fromJson(json) as Map<String, Any?>
    }

    @Test
    fun `send with tools produces an input_schema block, not parameters`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider()
        val tools = listOf(
            ToolSchema(
                name = "get_weather",
                description = "Get the weather",
                parameters = mapOf(
                    "location" to ToolParameter(type = "string", description = "City name", required = true)
                )
            )
        )
        p.send(p.startSession(), listOf(AssistantMessage.User(content = "weather?")), tools)

        val requestBody = requestBodyAsMap()
        val sentTools = requestBody["tools"] as List<Map<String, Any?>>
        assertThat(sentTools).hasSize(1)
        assertThat(sentTools[0]["name"]).isEqualTo("get_weather")
        assertThat(sentTools[0]["parameters"]).isNull()
        assertThat(sentTools[0]["input_schema"]).isNotNull()
        val inputSchema = sentTools[0]["input_schema"] as Map<String, Any?>
        assertThat(inputSchema["type"]).isEqualTo("object")
    }

    @Test
    fun `send with a System message produces a top-level system field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider()
        p.send(
            p.startSession(),
            listOf(
                AssistantMessage.System(content = "You are a helpful assistant."),
                AssistantMessage.User(content = "hi")
            ),
            emptyList()
        )

        val requestBody = requestBodyAsMap()
        assertThat(requestBody["system"]).isEqualTo("You are a helpful assistant.")
        val messages = requestBody["messages"] as List<Map<String, Any?>>
        assertThat(messages.none { it["role"] == "system" }).isTrue()
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `send builds tool_use and tool_result content blocks for a multi-turn tool conversation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider()
        p.send(
            p.startSession(),
            listOf(
                AssistantMessage.User(content = "what's the weather in Tokyo?"),
                AssistantMessage.Assistant(
                    content = "",
                    toolCalls = listOf(
                        ToolCallRequest(id = "toolu_1", name = "get_weather", arguments = """{"location":"Tokyo"}""")
                    )
                ),
                AssistantMessage.ToolCallResult(callId = "toolu_1", result = "72F and sunny")
            ),
            emptyList()
        )

        val requestBody = requestBodyAsMap()
        val messages = requestBody["messages"] as List<Map<String, Any?>>

        val assistantMessage = messages.first { it["role"] == "assistant" }
        val assistantContent = assistantMessage["content"] as List<Map<String, Any?>>
        val toolUseBlock = assistantContent.first { it["type"] == "tool_use" }
        assertThat(toolUseBlock["id"]).isEqualTo("toolu_1")
        assertThat(toolUseBlock["input"]).isEqualTo(mapOf("location" to "Tokyo"))

        val toolResultUserMessage = messages.last { it["role"] == "user" }
        val userContent = toolResultUserMessage["content"] as List<Map<String, Any?>>
        val toolResultBlock = userContent.first { it["type"] == "tool_result" }
        assertThat(toolResultBlock["tool_use_id"]).isEqualTo("toolu_1")
    }

    @Test
    fun `sendStreaming emits text deltas followed by a terminal delta with finishReason`() = runTest {
        val sse = """
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse))
        val p = provider()

        val deltas = p.sendStreaming(p.startSession(), listOf(AssistantMessage.User(content = "hi")), emptyList())
            .toList()

        assertThat(deltas.joinToString("") { it.contentDelta }).isEqualTo("Hi there")
        assertThat(deltas.last().finishReason).isEqualTo("end_turn")
    }
}
