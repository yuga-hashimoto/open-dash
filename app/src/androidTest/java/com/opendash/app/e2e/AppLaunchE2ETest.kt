package com.opendash.app.e2e

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-launch sanity check via UiAutomator.
 *
 * Launches the production [com.opendash.app.MainActivity] from a fresh
 * process and asserts that the first user-visible surface comes up
 * within [LAUNCH_TIMEOUT_MS]. Which surface that is depends on whether
 * the on-device LLM has already been downloaded on the test device:
 *
 * - First-ever install, no provider mode chosen yet → ProviderModeScreen
 *   ("Choose how OpenDash thinks" / "Local LLM" / "API provider").
 * - Local LLM chosen, model not yet downloaded → ModelSetupScreen
 *   ("Select AI Model" / "Loading models from HuggingFace..." /
 *   "Downloading" / "Failed").
 * - Fully set up → OnboardingScreen or ModeScaffold (the home / ambient
 *   surface). We accept any of these; the contract this test guards is
 *   "the app cold-starts without crashing and renders text", not which
 *   screen it lands on.
 *
 * If you change the labels on either screen, update [EXPECTED_TEXTS] so
 * this guard does not silently start passing on a blank screen.
 *
 * [HiltAndroidRule] is required even though this test injects nothing
 * itself: [com.opendash.app.HiltTestRunner] swaps in [dagger.hilt.android.testing.HiltTestApplication]
 * process-wide, and that application's Dagger component isn't created
 * until a `@HiltAndroidTest` test creates it — without the rule, the
 * real `MainActivity` launched below crashes with "The component was
 * not created" before it ever gets a chance to render.
 *
 * The runtime permissions the landing screens request immediately on
 * first launch (mic, notifications) are pre-granted via `pm grant`
 * before the app is started. On a genuinely fresh install those would
 * otherwise trigger a system permission dialog that owns the foreground
 * window — [By.pkg] then never matches this app's package, so
 * [cold_launch_renders_a_known_surface] would time out waiting on a
 * dialog nothing ever dismisses.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchE2ETest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        grantRuntimePermissions()
    }

    private fun grantRuntimePermissions() {
        val packageName = ApplicationProvider.getApplicationContext<android.content.Context>().packageName
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runShellCommand(automation, "pm grant $packageName android.permission.RECORD_AUDIO")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runShellCommand(automation, "pm grant $packageName android.permission.POST_NOTIFICATIONS")
        }
    }

    // Draining the output stream (not just closing the descriptor) blocks
    // until the shell command actually finishes, so the permission is
    // guaranteed granted before the launch intent below fires.
    private fun runShellCommand(automation: android.app.UiAutomation, command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    @Test
    fun cold_launch_renders_a_known_surface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageName = context.packageName

        // Bring the app to foreground from launcher state.
        device.pressHome()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        assertThat(launchIntent).isNotNull()
        context.startActivity(launchIntent)

        // Wait for the app's window to be in front.
        val appearedInForeground = device.wait(
            Until.hasObject(By.pkg(packageName).depth(0)),
            LAUNCH_TIMEOUT_MS
        )
        assertThat(appearedInForeground).isTrue()

        // Wait for any of the known landing-screen labels.
        val landed = EXPECTED_TEXTS.any { text ->
            device.wait(Until.hasObject(By.textContains(text)), SCREEN_TEXT_TIMEOUT_MS) != null
        }
        assertThat(landed).isTrue()
    }

    private companion object {
        // Generous: a cold first-ever-install launch competes with the test
        // runner's own dexing/build work for CPU on the host, and can take
        // noticeably longer than a warm launch.
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val SCREEN_TEXT_TIMEOUT_MS = 8_000L

        // Any one of these counts as "app rendered something". Order doesn't
        // matter — the loop polls each in turn.
        val EXPECTED_TEXTS = listOf(
            // ProviderModeScreen
            "Choose how",
            "Local LLM",
            "API provider",
            // ModelSetupScreen
            "Select AI Model",
            "Loading models",
            "Downloading",
            "Ready",
            "Failed",
            // OnboardingScreen / ModeScaffold (English fallback).
            "Welcome",
            "Get started",
            "Home",
            "Ambient"
        )
    }
}
