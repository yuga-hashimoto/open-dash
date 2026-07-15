package com.opendash.app.voice.pipeline

import android.content.Context
import com.opendash.app.assistant.provider.AssistantProvider
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.assistant.router.RoutingPolicy
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.service.VoiceService
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.SttResult
import com.opendash.app.voice.tts.TextToSpeech
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePipelineSttErrorTest {

    private lateinit var pipeline: VoicePipeline
    private lateinit var context: Context
    private lateinit var stt: SpeechToText
    private lateinit var tts: TextToSpeech
    private lateinit var router: ConversationRouter
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var provider: AssistantProvider
    private lateinit var preferences: AppPreferences
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        stt = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        router = mockk(relaxed = true)
        toolExecutor = mockk(relaxed = true)
        provider = mockk(relaxed = true)
        preferences = mockk(relaxed = true)

        every { stt.isListening } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        every { tts.stop() } returns Unit
        every { router.activeProvider } returns MutableStateFlow(provider)
        every { router.availableProviders } returns MutableStateFlow(listOf(provider))
        every { router.policy } returns MutableStateFlow(RoutingPolicy.Auto)
        val audioManager = mockk<android.media.AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { context.getSystemService(any<String>()) } returns audioManager
        every { preferences.observe<Boolean>(any()) } returns flowOf(null)
        every { preferences.observe<Long>(any()) } returns flowOf(null)
        every { preferences.observe<String>(any()) } returns flowOf(null)
        coEvery { toolExecutor.availableTools() } returns emptyList()

        pipeline = VoicePipeline(
            context = context,
            stt = stt,
            tts = tts,
            router = router,
            toolExecutor = toolExecutor,
            moshi = moshi,
            preferences = preferences
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `NO_MATCH returns quietly to Idle without Error state`() = runTest {
        mockkObject(VoiceService.Companion)
        try {
            every { VoiceService.pauseHotword(any()) } just Runs
            every { VoiceService.resumeHotword(any()) } just Runs
            every { stt.startListening() } returns flowOf(
                SttResult.Error("SpeechRecognizer error: NO_MATCH")
            )

            val states = mutableListOf<VoicePipelineState>()
            val job = launch { pipeline.state.collect { states.add(it) } }

            pipeline.startListening()
            advanceTimeBy(600)
            advanceUntilIdle()
            job.cancel()

            assertThat(pipeline.state.value).isEqualTo(VoicePipelineState.Idle)
            assertThat(pipeline.lastResponse.value).isEmpty()
            assertThat(states.any { it is VoicePipelineState.Error }).isFalse()
            verify(atLeast = 1) { VoiceService.resumeHotword(any()) }
        } finally {
            unmockkObject(VoiceService.Companion)
        }
    }

    @Test
    fun `SPEECH_TIMEOUT returns quietly to Idle`() = runTest {
        mockkObject(VoiceService.Companion)
        try {
            every { VoiceService.pauseHotword(any()) } just Runs
            every { VoiceService.resumeHotword(any()) } just Runs
            every { stt.startListening() } returns flowOf(
                SttResult.Error("SpeechRecognizer error: SPEECH_TIMEOUT")
            )

            pipeline.startListening()
            advanceTimeBy(600)
            advanceUntilIdle()

            assertThat(pipeline.state.value).isEqualTo(VoicePipelineState.Idle)
            assertThat(pipeline.lastResponse.value).isEmpty()
        } finally {
            unmockkObject(VoiceService.Companion)
        }
    }

    @Test
    fun `permission STT error surfaces Error state then recovers to Idle`() = runTest {
        mockkObject(VoiceService.Companion)
        try {
            every { VoiceService.pauseHotword(any()) } just Runs
            every { VoiceService.resumeHotword(any()) } just Runs
            every { stt.startListening() } returns flowOf(
                SttResult.Error("SpeechRecognizer error: INSUFFICIENT_PERMISSIONS")
            )

            val states = mutableListOf<VoicePipelineState>()
            val collectJob = launch { pipeline.state.collect { states.add(it) } }

            pipeline.startListening()
            advanceTimeBy(600)
            advanceUntilIdle()
            advanceTimeBy(2_500)
            advanceUntilIdle()
            collectJob.cancel()

            assertThat(states.any { it is VoicePipelineState.Error }).isTrue()
            assertThat(pipeline.state.value).isEqualTo(VoicePipelineState.Idle)
            assertThat(pipeline.lastResponse.value.lowercase()).contains("permission")
            verify(atLeast = 1) { VoiceService.resumeHotword(any()) }
        } finally {
            unmockkObject(VoiceService.Companion)
        }
    }

    @Test
    fun `network STT error surfaces actionable Error state then recovers`() = runTest {
        mockkObject(VoiceService.Companion)
        try {
            every { VoiceService.pauseHotword(any()) } just Runs
            every { VoiceService.resumeHotword(any()) } just Runs
            every { stt.startListening() } returns flowOf(
                SttResult.Error("SpeechRecognizer error: NETWORK")
            )

            val states = mutableListOf<VoicePipelineState>()
            val collectJob = launch { pipeline.state.collect { states.add(it) } }

            pipeline.startListening()
            advanceTimeBy(600)
            advanceUntilIdle()
            advanceTimeBy(2_500)
            advanceUntilIdle()
            collectJob.cancel()

            assertThat(states.any { it is VoicePipelineState.Error }).isTrue()
            assertThat(pipeline.lastResponse.value.lowercase()).contains("network")
            assertThat(pipeline.state.value).isEqualTo(VoicePipelineState.Idle)
            verify(atLeast = 1) { VoiceService.resumeHotword(any()) }
        } finally {
            unmockkObject(VoiceService.Companion)
        }
    }

    @Test
    fun `recognizer unavailable surfaces Error state then recovers`() = runTest {
        mockkObject(VoiceService.Companion)
        try {
            every { VoiceService.pauseHotword(any()) } just Runs
            every { VoiceService.resumeHotword(any()) } just Runs
            every { stt.startListening() } returns flowOf(
                SttResult.Error("Speech recognition not available")
            )

            val states = mutableListOf<VoicePipelineState>()
            val collectJob = launch { pipeline.state.collect { states.add(it) } }

            pipeline.startListening()
            advanceTimeBy(600)
            advanceUntilIdle()
            advanceTimeBy(2_500)
            advanceUntilIdle()
            collectJob.cancel()

            assertThat(states.any { it is VoicePipelineState.Error }).isTrue()
            assertThat(pipeline.lastResponse.value).isNotEmpty()
            assertThat(pipeline.state.value).isEqualTo(VoicePipelineState.Idle)
        } finally {
            unmockkObject(VoiceService.Companion)
        }
    }
}
