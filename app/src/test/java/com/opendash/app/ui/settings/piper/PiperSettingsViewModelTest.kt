package com.opendash.app.ui.settings.piper

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.voice.tts.piper.PiperVoiceCatalog
import com.opendash.app.voice.tts.piper.PiperVoiceDownloader
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Synchronous-only coverage, matching the Whisper settings VM test
 * rationale — real async download paths are verified in
 * [com.opendash.app.voice.tts.piper.PiperVoiceDownloaderTest] with
 * MockWebServer. Avoiding `Dispatchers.setMain` here keeps other test
 * suites unaffected.
 */
class PiperSettingsViewModelTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: PiperVoiceDownloader

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("piper-ui-vm-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = PiperVoiceDownloader(context)
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `rows initial value has one entry per catalogue voice`() {
        val vm = PiperSettingsViewModel(downloader)
        val rows = vm.rows.value
        assertThat(rows).hasSize(PiperVoiceCatalog.all.size)
        assertThat(rows.map { it.voice.id })
            .containsExactlyElementsIn(PiperVoiceCatalog.all.map { it.id })
            .inOrder()
        assertThat(rows.all { !it.installed }).isTrue()
        assertThat(rows.all { !it.isDownloading }).isTrue()
        assertThat(rows.all { it.totalMb == it.voice.modelSizeMb }).isTrue()
    }

    @Test
    fun `delete forwards to downloader deleteVoice`() {
        val target = PiperVoiceCatalog.EN_US_AMY_MEDIUM
        val piperDir = File(tempDir, "piper").apply { mkdirs() }
        File(piperDir, target.modelFilename).writeBytes(ByteArray(4096))
        File(piperDir, target.configFilename).writeBytes(ByteArray(128))
        assertThat(downloader.isDownloaded(target)).isTrue()

        val vm = PiperSettingsViewModel(downloader)
        vm.delete(target)

        assertThat(downloader.isDownloaded(target)).isFalse()
    }
}
