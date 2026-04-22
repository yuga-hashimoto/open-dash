package com.opendash.app.assistant.agent

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.ToolCallRequest
import org.junit.jupiter.api.Test

class StreamingToolCallAggregatorTest {

    @Test
    fun `single complete delta is returned as-is`() {
        val agg = StreamingToolCallAggregator()
        agg.accept(ToolCallRequest(id = "call_1", name = "get_weather", arguments = "{\"loc\":\"Tokyo\"}"))
        val out = agg.complete()
        assertThat(out).hasSize(1)
        assertThat(out[0].id).isEqualTo("call_1")
        assertThat(out[0].name).isEqualTo("get_weather")
        assertThat(out[0].arguments).isEqualTo("{\"loc\":\"Tokyo\"}")
    }

    @Test
    fun `arguments fragments with empty id append to the pending call`() {
        val agg = StreamingToolCallAggregator()
        // Canonical OpenAI streaming sequence:
        //   {id:"call_1", name:"get_weather", arguments:""}
        //   {id:"",       name:"",            arguments:"{\"loc\":"}
        //   {id:"",       name:"",            arguments:"\"Tokyo\"}"}
        agg.accept(ToolCallRequest(id = "call_1", name = "get_weather", arguments = ""))
        agg.accept(ToolCallRequest(id = "", name = "", arguments = "{\"loc\":"))
        agg.accept(ToolCallRequest(id = "", name = "", arguments = "\"Tokyo\"}"))

        val out = agg.complete()
        assertThat(out).hasSize(1)
        assertThat(out[0].id).isEqualTo("call_1")
        assertThat(out[0].name).isEqualTo("get_weather")
        assertThat(out[0].arguments).isEqualTo("{\"loc\":\"Tokyo\"}")
    }

    @Test
    fun `new non-empty id starts a new call`() {
        val agg = StreamingToolCallAggregator()
        agg.accept(ToolCallRequest(id = "call_1", name = "get_weather", arguments = "{}"))
        agg.accept(ToolCallRequest(id = "call_2", name = "get_time", arguments = "{}"))

        val out = agg.complete()
        assertThat(out).hasSize(2)
        assertThat(out.map { it.id }).containsExactly("call_1", "call_2").inOrder()
        assertThat(out.map { it.name }).containsExactly("get_weather", "get_time").inOrder()
    }

    @Test
    fun `name fragment arriving after id continues the pending call`() {
        val agg = StreamingToolCallAggregator()
        // Some providers emit name piecemeal too; sticky behaviour:
        // when id is empty and name is non-empty, we treat the name as
        // an override-or-append signal for the pending call.
        agg.accept(ToolCallRequest(id = "call_1", name = "get_", arguments = ""))
        agg.accept(ToolCallRequest(id = "", name = "weather", arguments = ""))
        agg.accept(ToolCallRequest(id = "", name = "", arguments = "{}"))

        val out = agg.complete()
        assertThat(out).hasSize(1)
        assertThat(out[0].name).isEqualTo("get_weather")
    }

    @Test
    fun `fragment before any pending call becomes a standalone call`() {
        val agg = StreamingToolCallAggregator()
        // Edge: provider emits an args-only fragment first. We treat it
        // as a full call with empty id/name rather than silently dropping
        // it — downstream will surface it as a tool failure, which is
        // strictly better than losing the call.
        agg.accept(ToolCallRequest(id = "", name = "", arguments = "{}"))
        val out = agg.complete()
        assertThat(out).hasSize(1)
        assertThat(out[0].arguments).isEqualTo("{}")
    }

    @Test
    fun `accepting empty chunk is a no-op`() {
        val agg = StreamingToolCallAggregator()
        agg.accept(ToolCallRequest(id = "call_1", name = "get_weather", arguments = "{}"))
        agg.accept(ToolCallRequest(id = "", name = "", arguments = ""))
        val out = agg.complete()
        assertThat(out).hasSize(1)
        assertThat(out[0].arguments).isEqualTo("{}")
    }

    @Test
    fun `complete on empty aggregator returns empty list`() {
        val agg = StreamingToolCallAggregator()
        assertThat(agg.complete()).isEmpty()
    }
}
