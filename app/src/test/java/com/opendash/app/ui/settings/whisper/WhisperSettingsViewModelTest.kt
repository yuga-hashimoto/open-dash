package com.opendash.app.ui.settings.whisper

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
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

/**
 * Synchronous-only coverage of the row / active-model surface. The
 * async download lifecycle is validated in
 * [com.opendash.app.voice.stt.whisper.WhisperModelDownloaderTest] with
 * MockWebServer.
 */
class WhisperSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: WhisperModelDownloader
    private lateinit var preferences: AppPreferences

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("whisper-ui-vm-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = WhisperModelDownloader(context)
        preferences = mockk(relaxed = true)
        every {
            preferences.observe(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID)
        } returns flowOf(null as String?)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `rows initial value has one entry per catalogue model with default active`() {
        val vm = WhisperSettingsViewModel(downloader, preferences)
        val rows = vm.rows.value
        assertThat(rows).hasSize(WhisperModelCatalog.all.size)
        // The first (smallest) model is the catalogue default — should
        // be marked active when the preference is unset.
        val activeRow = rows.first { it.isActive }
        assertThat(activeRow.model.id).isEqualTo(WhisperModelCatalog.default.id)
        assertThat(rows.count { it.isActive }).isEqualTo(1)
        assertThat(rows.all { !it.installed }).isTrue()
        assertThat(rows.all { !it.isDownloading }).isTrue()
    }

    @Test
    fun `delete forwards to downloader deleteModel`() {
        val target = WhisperModelCatalog.TINY_Q5_1
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        File(whisperDir, target.filename).writeBytes(ByteArray(4096))
        assertThat(downloader.isDownloaded(target)).isTrue()

        val vm = WhisperSettingsViewModel(downloader, preferences)
        vm.delete(target)

        assertThat(downloader.isDownloaded(target)).isFalse()
    }

    @Test
    fun `setActive persists the id to preferences`() {
        val vm = WhisperSettingsViewModel(downloader, preferences)
        vm.setActive(WhisperModelCatalog.BASE_Q5_1)

        coVerify {
            preferences.set(
                PreferenceKeys.WHISPER_ACTIVE_MODEL_ID,
                WhisperModelCatalog.BASE_Q5_1.id
            )
        }
    }
}
