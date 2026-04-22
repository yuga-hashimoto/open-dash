package com.opendash.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.assistant.agent.AgentToolDispatcher
import com.opendash.app.assistant.agent.StreamingToolCallAggregator
import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.AssistantSession
import com.opendash.app.assistant.model.ConversationState
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.voice.pipeline.VoicePipeline
import com.opendash.app.voice.pipeline.VoicePipelineState
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.SttResult
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val router: ConversationRouter,
    private val toolExecutor: ToolExecutor,
    private val moshi: Moshi,
    private val voicePipeline: VoicePipeline,
    private val stt: SpeechToText
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _conversationState = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val toolDispatcher = AgentToolDispatcher(toolExecutor, moshi)

    private var session: AssistantSession? = null

    val voiceState: StateFlow<VoicePipelineState> = voicePipeline.state

    companion object {
        private const val MAX_TOOL_ROUNDS = 10
    }

    fun startVoiceInput() {
        viewModelScope.launch {
            _conversationState.value = ConversationState.Listening

            var recognizedText = ""
            stt.startListening().collect { result ->
                when (result) {
                    is SttResult.Final -> recognizedText = result.text
                    is SttResult.Partial -> { /* UI could show partial */ }
                    is SttResult.Error -> {
                        _conversationState.value = ConversationState.Error(result.message)
                        return@collect
                    }
                }
            }

            if (recognizedText.isNotBlank()) {
                sendMessage(recognizedText)
            } else {
                _conversationState.value = ConversationState.Idle
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val userMessage = AssistantMessage.User(content = text)
            _messages.value = _messages.value + userMessage
            _conversationState.value = ConversationState.Thinking

            try {
                val provider = router.resolveProvider(userInput = text)
                if (session == null) {
                    session = provider.startSession()
                }

                val tools = toolExecutor.availableTools()
                val conversationMessages = _messages.value.toMutableList()
                var toolRounds = 0

                while (toolRounds < MAX_TOOL_ROUNDS) {
                    _streamingContent.value = ""
                    val responseBuilder = StringBuilder()
                    val toolCallAggregator = StreamingToolCallAggregator()

                    provider.sendStreaming(session!!, conversationMessages, tools)
                        .collect { delta ->
                            responseBuilder.append(delta.contentDelta)
                            _streamingContent.value = responseBuilder.toString()
                            delta.toolCallDelta?.let { toolCallAggregator.accept(it) }
                        }
                    val toolCalls = toolCallAggregator.complete()

                    val assistantResponse = AssistantMessage.Assistant(
                        content = responseBuilder.toString(),
                        toolCalls = toolCalls
                    )
                    conversationMessages.add(assistantResponse)

                    if (toolCalls.isNotEmpty()) {
                        val results = toolDispatcher.dispatch(toolCalls)
                        conversationMessages.addAll(results)
                        toolRounds++
                        continue
                    }

                    _messages.value = _messages.value + assistantResponse
                    _streamingContent.value = ""
                    _conversationState.value = ConversationState.Idle
                    return@launch
                }

                Timber.w("Max tool rounds ($MAX_TOOL_ROUNDS) reached")
                _streamingContent.value = ""
                _conversationState.value = ConversationState.Idle
            } catch (e: Exception) {
                Timber.e(e, "Failed to send message")
                _streamingContent.value = ""
                _conversationState.value = ConversationState.Error(e.message ?: "Unknown error")
            }
        }
    }

}
