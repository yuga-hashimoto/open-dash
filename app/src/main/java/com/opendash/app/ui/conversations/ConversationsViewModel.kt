package com.opendash.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.db.MessageDao
import com.opendash.app.data.db.MessageEntity
import com.opendash.app.data.db.SessionDao
import com.opendash.app.data.db.SessionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** One session worth of messages, pre-sorted ascending by timestamp. */
data class ConversationSummary(
    val session: SessionEntity,
    val messages: List<MessageEntity>,
) {
    val preview: String = messages
        .firstOrNull { it.role == "user" }
        ?.content
        ?.take(PREVIEW_CHARS)
        ?: messages.firstOrNull()?.content?.take(PREVIEW_CHARS)
        ?: ""

    val messageCount: Int = messages.size

    companion object {
        private const val PREVIEW_CHARS = 60
    }
}

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _expandedSessionId = MutableStateFlow<String?>(null)
    val expandedSessionId: StateFlow<String?> = _expandedSessionId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val summaries = sessionDao.getAll().map { session ->
                    ConversationSummary(
                        session = session,
                        messages = messageDao.getBySessionId(session.id),
                    )
                }
                _conversations.value = summaries
            } catch (e: Exception) {
                Timber.e(e, "Failed to load conversation history")
                _conversations.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleExpanded(sessionId: String) {
        _expandedSessionId.value = if (_expandedSessionId.value == sessionId) null else sessionId
    }
}
