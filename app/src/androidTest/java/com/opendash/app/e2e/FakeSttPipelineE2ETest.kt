package com.opendash.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.opendash.app.e2e.fakes.FakeSpeechToText
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.SttResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Verifies the [SpeechToText] @TestInstallIn swap works end-to-end:
 * the Hilt graph hands every consumer the [FakeSpeechToText] singleton,
 * and queued results flow through to a collector.
 *
 * `VoicePipeline.startListening()` itself is service-aware (audio focus,
 * VoiceService wake-word pause, beep playback) so wiring the **full**
 * wake→STT→tool→TTS chain into an emulator @Test is left to a follow-up
 * PR that uses `androidx.test.rule.ServiceTestRule` (or an extracted
 * non-service `AudioFocusController` shim). Until then, this guard at
 * least catches DI regressions on the STT swap pattern.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FakeSttPipelineE2ETest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var fakeStt: FakeSpeechToText
    @Inject lateinit var realInterface: SpeechToText

    @Before
    fun setUp() {
        hiltRule.inject()
        fakeStt.reset()
    }

    @Test
    fun hilt_resolves_fake_for_speech_to_text_interface() {
        // Both injection points must resolve to the same singleton —
        // otherwise tests that inject the interface (e.g. via a real
        // production component) would not see queued results.
        assertThat(realInterface).isSameInstanceAs(fakeStt)
    }

    @Test
    fun queued_results_emit_in_order_and_terminate_on_final() = runBlocking {
        fakeStt.queue(SttResult.Partial("set timer"))
        fakeStt.queue(SttResult.Partial("set timer for 5"))
        fakeStt.queue(SttResult.Final("set timer for 5 minutes"))

        val collected = realInterface.startListening().toList()

        assertThat(collected).hasSize(3)
        assertThat(collected[0]).isEqualTo(SttResult.Partial("set timer"))
        assertThat(collected[2]).isInstanceOf(SttResult.Final::class.java)
        assertThat((collected[2] as SttResult.Final).text)
            .isEqualTo("set timer for 5 minutes")
    }

    @Test
    fun error_result_terminates_listening() = runBlocking {
        fakeStt.queue(SttResult.Partial("hello"))
        fakeStt.queue(SttResult.Error("NETWORK"))

        val collected = realInterface.startListening().toList()

        assertThat(collected).hasSize(2)
        assertThat(collected[1]).isInstanceOf(SttResult.Error::class.java)
    }

    @Test
    fun reset_allows_a_second_listening_session() = runBlocking {
        fakeStt.queue(SttResult.Final("first"))
        val first = realInterface.startListening().toList()
        assertThat(first).hasSize(1)

        // Without reset (or implicit channel renewal in startListening), a
        // second call would see a closed channel.
        fakeStt.queue(SttResult.Final("second"))
        val second = realInterface.startListening().toList()
        assertThat(second).hasSize(1)
        assertThat((second[0] as SttResult.Final).text).isEqualTo("second")
    }
}
