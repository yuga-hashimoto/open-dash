package com.opendash.app.ui.settings.whisper

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Scope note: this suite covers the VM's *synchronous* surface — row
 * shape, catalogue ordering, and `delete` delegation to the injected
 * downloader. Neither test touches `viewModelScope.launch` so we skip
 * `Dispatchers.setMain` entirely — avoiding the cross-class Main leak
 * that dirties other test suites.
 *
 * The async download lifecycle (StateFlow → combine → installed flag)
 * is covered by [com.opendash.app.voice.stt.whisper.WhisperModelDownloaderTest]
 * with MockWebServer. The Compose screen is verified end-to-end via
 * the real-device smoke path once externalNativeBuild is re-enabled.
 */
class WhisperSettingsViewModelTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: WhisperModelDownloader

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("whisper-ui-vm-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = WhisperModelDownloader(context)
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `rows initial value has one entry per catalogue model`() {
        val vm = WhisperSettingsViewModel(downloader)
        // StateFlow returns the initialValue synchronously; stateIn with
        // WhileSubscribed never starts collecting without a subscriber,
        // so no viewModelScope coroutines launch here.
        val rows = vm.rows.value
        assertThat(rows).hasSize(WhisperModelCatalog.all.size)
        assertThat(rows.map { it.model.id }).containsExactlyElementsIn(
            WhisperModelCatalog.all.map { it.id }
        ).inOrder()
        assertThat(rows.all { !it.installed }).isTrue()
        assertThat(rows.all { !it.isDownloading }).isTrue()
        assertThat(rows.all { it.totalMb == it.model.sizeMb }).isTrue()
    }

    @Test
    fun `delete forwards to downloader deleteModel`() {
        val target = WhisperModelCatalog.TINY_Q5_1
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        File(whisperDir, target.filename).writeBytes(ByteArray(4096))
        assertThat(downloader.isDownloaded(target)).isTrue()

        val vm = WhisperSettingsViewModel(downloader)
        vm.delete(target)

        assertThat(downloader.isDownloaded(target)).isFalse()
    }
}
