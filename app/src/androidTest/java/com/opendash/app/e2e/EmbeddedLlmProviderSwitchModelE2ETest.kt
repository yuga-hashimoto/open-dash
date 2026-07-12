package com.opendash.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmConfig
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises [EmbeddedLlmProvider.switchModel] (P16.6) against the real
 * `com.google.ai.edge.litertlm.Engine` native library — a plain JVM unit
 * test can't load that `.so`, same constraint as every other native
 * integration this project ships (Whisper, Silero VAD, QuickJS, ...).
 *
 * Not yet run in this environment — no device/emulator was available during
 * implementation. `Engine.close()` was confirmed to exist via `javap`
 * against the resolved `litertlm-android` AAR (it implements
 * `java.lang.AutoCloseable`), so the teardown+reinit sequence this test
 * checks is API-supported; what's unverified is whether it behaves cleanly
 * (no native leak/crash) on real hardware across GPU vendors.
 *
 * This intentionally never points [switchModel] at a real, working model —
 * downloading a multi-GB model file isn't something a test should do. It
 * targets the failure/revert path instead: an invalid model path must fail
 * to initialize and [switchModel] must revert the provider back to serving
 * the model it had before, rather than leaving it dead.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedLlmProviderSwitchModelE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun switchModel_toAnInvalidPath_failsAndRevertsRatherThanCrashing() = runBlocking {
        val originalModel = File.createTempFile("original-model", ".task")
        val provider = EmbeddedLlmProvider(
            context = context,
            initialConfig = EmbeddedLlmConfig(modelPath = originalModel.absolutePath)
        )

        val result = provider.switchModel("/nonexistent/path/does-not-exist.task")

        assertThat(result).isFalse()
        // isAvailable() falls back to "does the configured file exist" when
        // no engine is loaded; after a failed swap + revert this should be
        // the original path again, not the bogus one.
        assertThat(provider.isAvailable()).isTrue()
    }
}
