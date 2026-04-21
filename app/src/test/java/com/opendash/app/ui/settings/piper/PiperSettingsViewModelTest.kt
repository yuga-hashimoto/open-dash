package com.opendash.app.ui.settings.piper

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.tts.piper.PiperVoiceCatalog
import com.opendash.app.voice.tts.piper.PiperVoiceDownloader
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class PiperSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: PiperVoiceDownloader
    private lateinit var preferences: AppPreferences

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("piper-ui-vm-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = PiperVoiceDownloader(context)
        preferences = mockk(relaxed = true)
        every {
            preferences.observe(PreferenceKeys.PIPER_ACTIVE_VOICE_ID)
        } returns flowOf(null as String?)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `rows initial value has one entry per catalogue voice with default active`() {
        val vm = PiperSettingsViewModel(downloader, preferences)
        val rows = vm.rows.value
        assertThat(rows).hasSize(PiperVoiceCatalog.all.size)
        val active = rows.first { it.isActive }
        assertThat(active.voice.id).isEqualTo(PiperVoiceCatalog.default.id)
        assertThat(rows.count { it.isActive }).isEqualTo(1)
    }

    @Test
    fun `delete forwards to downloader deleteVoice`() {
        val target = PiperVoiceCatalog.EN_US_AMY_MEDIUM
        val piperDir = File(tempDir, "piper").apply { mkdirs() }
        File(piperDir, target.modelFilename).writeBytes(ByteArray(4096))
        File(piperDir, target.configFilename).writeBytes(ByteArray(128))
        assertThat(downloader.isDownloaded(target)).isTrue()

        val vm = PiperSettingsViewModel(downloader, preferences)
        vm.delete(target)

        assertThat(downloader.isDownloaded(target)).isFalse()
    }

    @Test
    fun `setActive persists the id to preferences`() {
        val vm = PiperSettingsViewModel(downloader, preferences)
        vm.setActive(PiperVoiceCatalog.JA_JP_TAKUMI_MEDIUM)

        coVerify {
            preferences.set(
                PreferenceKeys.PIPER_ACTIVE_VOICE_ID,
                PiperVoiceCatalog.JA_JP_TAKUMI_MEDIUM.id
            )
        }
    }
}
