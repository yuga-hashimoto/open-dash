package com.opendash.app.assistant.provider.anthropic

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.jupiter.api.Test

class AnthropicStreamParserTest {

    private val parser = AnthropicStreamParser(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())

    @Test
    fun `parseLine extracts text_delta content`() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.contentDelta).isEqualTo("Hello")
    }

    @Test
    fun `parseLine returns null for non-data lines`() {
        assertThat(parser.parseLine("event: content_block_delta")).isNull()
        assertThat(parser.parseLine("")).isNull()
    }

    @Test
    fun `parseLine extracts finish reason from message_delta`() {
        val line = """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.finishReason).isEqualTo("end_turn")
    }

    @Test
    fun `parseLine emits a tool call header on content_block_start with tool_use`() {
        val line = """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.toolCallDelta).isNotNull()
        assertThat(delta.toolCallDelta!!.id).isEqualTo("toolu_1")
        assertThat(delta.toolCallDelta.name).isEqualTo("get_weather")
    }

    @Test
    fun `parseLine emits a tool call argument fragment on input_json_delta`() {
        val line = """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.toolCallDelta).isNotNull()
        assertThat(delta.toolCallDelta!!.id).isEmpty()
        assertThat(delta.toolCallDelta.arguments).isEqualTo("{\"location\":")
    }

    @Test
    fun `parseFullResponse concatenates text blocks`() {
        val json = """{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}],"stop_reason":"end_turn"}"""
        val msg = parser.parseFullResponse(json) as AssistantMessage.Assistant
        assertThat(msg.content).isEqualTo("Hi there")
        assertThat(msg.toolCalls).isEmpty()
    }

    @Test
    fun `parseFullResponse extracts tool_use blocks as tool calls`() {
        val json = """{"content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"location":"Tokyo"}}],"stop_reason":"tool_use"}"""
        val msg = parser.parseFullResponse(json) as AssistantMessage.Assistant
        assertThat(msg.toolCalls).hasSize(1)
        assertThat(msg.toolCalls.first().id).isEqualTo("toolu_1")
        assertThat(msg.toolCalls.first().name).isEqualTo("get_weather")
        assertThat(msg.toolCalls.first().arguments).contains("Tokyo")
    }
}
