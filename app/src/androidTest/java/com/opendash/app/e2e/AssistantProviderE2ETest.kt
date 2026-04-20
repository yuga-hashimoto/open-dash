package com.opendash.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.assistant.router.RoutingPolicy
import com.opendash.app.e2e.fakes.FakeAssistantProvider
import com.opendash.app.e2e.fakes.FakeTextToSpeech
import com.opendash.app.voice.pipeline.VoicePipeline
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Full LLM round-trip E2E.
 *
 * Drives the **real** [VoicePipeline] through a non-fast-path utterance
 * so it falls into the LLM agent loop:
 *
 *   processUserInput → ConversationRouter.resolveProvider →
 *   FakeAssistantProvider.send → tts.speak(response.content)
 *
 * The fake assistant is registered via the production
 * `ConversationRouter.registerProvider` API — no `@TestInstallIn`
 * needed because the router accepts dynamically-added providers and
 * we make the fake the only available one for this test by setting
 * Manual policy.
 *
 * TTS is the same `FakeTextToSpeech` from #460 (already on main),
 * resolved by Hilt because `FakeTtsTestModule` is an
 * `@TestInstallIn` that runs in the test APK.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AssistantProviderE2ETest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var fakeTts: FakeTextToSpeech
    @Inject lateinit var conversationRouter: ConversationRouter

    private lateinit var fakeAssistant: FakeAssistantProvider

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        fakeTts.reset()

        // Register a fresh fake provider per test and force the router
        // to use it via Manual policy. This deliberately bypasses any
        // production providers that the model-download flow may have
        // registered — we want hermetic LLM-call assertions.
        fakeAssistant = FakeAssistantProvider()
        conversationRouter.registerProvider(fakeAssistant)
        conversationRouter.selectProvider(fakeAssistant.id)
    }

    @Test
    fun llm_response_is_spoken_via_tts() = runBlocking {
        val canned = "Quantum computing uses qubits."
        fakeAssistant.queueResponse(AssistantMessage.Assistant(content = canned))

        // Ambiguous information query — AgentIntentHint forces a fall-
        // through to the LLM path, so this guarantees we hit
        // FakeAssistantProvider.send and not a fast-path matcher.
        val utterance = "explain quantum computing"

        withTimeout(PIPELINE_TIMEOUT_MS) {
            voicePipeline.processUserInput(utterance)
        }

        // The provider was actually consulted with the user message.
        assertThat(fakeAssistant.sentMessages).isNotEmpty()
        val lastBatch = fakeAssistant.sentMessages.last()
        val userInBatch = lastBatch.filterIsInstance<AssistantMessage.User>()
        assertThat(userInBatch.any { it.content == utterance }).isTrue()

        // The canned response was spoken.
        assertThat(fakeTts.spokenTexts).contains(canned)
    }

    @Test
    fun fake_provider_is_resolved_under_manual_policy() = runBlocking {
        // Independent guard: setUp already selected fakeAssistant. Make
        // sure resolveProvider returns it so future tests don't get
        // silently routed to a real provider if model download finishes
        // mid-suite.
        assertThat(conversationRouter.policy.value)
            .isInstanceOf(RoutingPolicy.Manual::class.java)
        val resolved = conversationRouter.resolveProvider("hello")
        assertThat(resolved.id).isEqualTo(fakeAssistant.id)
    }

    private companion object {
        // LLM round-trip is two pipeline phases (Thinking + Speaking)
        // plus filler-phrase debounce. Generous to ride out emulator
        // variance.
        const val PIPELINE_TIMEOUT_MS = 10_000L
    }
}
