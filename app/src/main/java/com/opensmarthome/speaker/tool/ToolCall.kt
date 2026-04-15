package com.opensmarthome.speaker.tool

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)
