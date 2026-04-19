package com.opendash.app.ui.conversations

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.db.MessageDao
import com.opendash.app.data.db.MessageEntity
import com.opendash.app.data.db.SessionDao
import com.opendash.app.data.db.SessionEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads sessions with messages from DAOs`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val messageDao = mockk<MessageDao>()

        val session1 = SessionEntity(id = "s1", providerId = "local", createdAt = 1000L)
        val session2 = SessionEntity(id = "s2", providerId = "local", createdAt = 2000L)
        coEvery { sessionDao.getAll() } returns listOf(session2, session1)
        coEvery { messageDao.getBySessionId("s1") } returns listOf(
            MessageEntity("m1", "s1", "user", "Hello", 1001L),
            MessageEntity("m2", "s1", "assistant", "Hi there", 1002L),
        )
        coEvery { messageDao.getBySessionId("s2") } returns listOf(
            MessageEntity("m3", "s2", "user", "What's the weather?", 2001L),
        )

        val viewModel = ConversationsViewModel(sessionDao, messageDao)
        advanceUntilIdle()

        val conversations = viewModel.conversations.value
        assertThat(conversations).hasSize(2)
        assertThat(conversations[0].session.id).isEqualTo("s2")
        assertThat(conversations[0].messages).hasSize(1)
        assertThat(conversations[1].session.id).isEqualTo("s1")
        assertThat(conversations[1].messages).hasSize(2)
    }

    @Test
    fun `preview picks first user message content`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val messageDao = mockk<MessageDao>()
        val session = SessionEntity(id = "s1", providerId = "local", createdAt = 1000L)
        coEvery { sessionDao.getAll() } returns listOf(session)
        coEvery { messageDao.getBySessionId("s1") } returns listOf(
            MessageEntity("m0", "s1", "system", "You are Dash.", 999L),
            MessageEntity("m1", "s1", "user", "Turn on the lights", 1001L),
            MessageEntity("m2", "s1", "assistant", "Turning on", 1002L),
        )

        val viewModel = ConversationsViewModel(sessionDao, messageDao)
        advanceUntilIdle()

        assertThat(viewModel.conversations.value.single().preview)
            .isEqualTo("Turn on the lights")
    }

    @Test
    fun `toggleExpanded flips expansion state`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val messageDao = mockk<MessageDao>()
        coEvery { sessionDao.getAll() } returns emptyList()

        val viewModel = ConversationsViewModel(sessionDao, messageDao)
        advanceUntilIdle()

        assertThat(viewModel.expandedSessionId.value).isNull()
        viewModel.toggleExpanded("s1")
        assertThat(viewModel.expandedSessionId.value).isEqualTo("s1")
        viewModel.toggleExpanded("s1")
        assertThat(viewModel.expandedSessionId.value).isNull()
        viewModel.toggleExpanded("s1")
        viewModel.toggleExpanded("s2")
        assertThat(viewModel.expandedSessionId.value).isEqualTo("s2")
    }

    @Test
    fun `DAO failure results in empty list`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val messageDao = mockk<MessageDao>()
        coEvery { sessionDao.getAll() } throws RuntimeException("db error")

        val viewModel = ConversationsViewModel(sessionDao, messageDao)
        advanceUntilIdle()

        assertThat(viewModel.conversations.value).isEmpty()
        assertThat(viewModel.isLoading.value).isFalse()
    }
}
