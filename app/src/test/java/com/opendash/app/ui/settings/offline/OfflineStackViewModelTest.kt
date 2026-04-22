package com.opendash.app.ui.settings.offline

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.voice.stt.whisper.WhisperModelCatalog
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import com.opendash.app.voice.tts.piper.PiperVoiceCatalog
import com.opendash.app.voice.tts.piper.PiperVoiceDownloader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStackViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var whisperDownloader: WhisperModelDownloader
    private lateinit var piperDownloader: PiperVoiceDownloader
    private lateinit var preferences: AppPreferences

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("offline-diag-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        whisperDownloader = WhisperModelDownloader(context)
        piperDownloader = PiperVoiceDownloader(context)
        preferences = mockk(relaxed = true)
        every { preferences.observe(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID) } returns flowOf(null as String?)
        every { preferences.observe(PreferenceKeys.PIPER_ACTIVE_VOICE_ID) } returns flowOf(null as String?)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `pristine state reports everything missing and default names`() {
        val vm = OfflineStackViewModel(preferences, whisperDownloader, piperDownloader)
        val s = vm.state.value

        // Native libs aren't loaded in JVM tests.
        assertThat(s.whisperLibraryLoaded).isEqualTo(OfflineStackViewModel.Status.Missing)
        assertThat(s.piperLibraryLoaded).isEqualTo(OfflineStackViewModel.Status.Missing)
        // No downloads yet.
        assertThat(s.whisperActiveModelDownloaded).isEqualTo(OfflineStackViewModel.Status.Missing)
        assertThat(s.piperActiveVoiceDownloaded).isEqualTo(OfflineStackViewModel.Status.Missing)
        // Overall inherits worst-of.
        assertThat(s.whisperOverall).isEqualTo(OfflineStackViewModel.Status.Missing)
        assertThat(s.piperOverall).isEqualTo(OfflineStackViewModel.Status.Missing)
        // Active names default to catalogue defaults.
        assertThat(s.whisperActiveModelName).isEqualTo(WhisperModelCatalog.default.displayName)
        assertThat(s.piperActiveVoiceName).isEqualTo(PiperVoiceCatalog.default.displayName)
    }

    @Test
    fun `downloading the default Whisper model flips the model row to Ready`() {
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        File(whisperDir, WhisperModelCatalog.default.filename).writeBytes(ByteArray(4096))

        val vm = OfflineStackViewModel(preferences, whisperDownloader, piperDownloader)

        val s = vm.state.value
        assertThat(s.whisperActiveModelDownloaded).isEqualTo(OfflineStackViewModel.Status.Ready)
        // Overall still Missing because the native lib isn't loaded on JVM.
        assertThat(s.whisperOverall).isEqualTo(OfflineStackViewModel.Status.Missing)
    }

    @Test
    fun `downloading the default Piper voice flips the voice row to Ready`() {
        val piperDir = File(tempDir, "piper").apply { mkdirs() }
        val v = PiperVoiceCatalog.default
        File(piperDir, v.modelFilename).writeBytes(ByteArray(4096))
        File(piperDir, v.configFilename).writeBytes(ByteArray(128))

        val vm = OfflineStackViewModel(preferences, whisperDownloader, piperDownloader)

        val s = vm.state.value
        assertThat(s.piperActiveVoiceDownloaded).isEqualTo(OfflineStackViewModel.Status.Ready)
        assertThat(s.piperOverall).isEqualTo(OfflineStackViewModel.Status.Missing)
    }

    @Test
    fun `unknown active id falls back to catalogue default`() {
        every { preferences.observe(PreferenceKeys.WHISPER_ACTIVE_MODEL_ID) } returns flowOf("ghost-model-id")
        val vm = OfflineStackViewModel(preferences, whisperDownloader, piperDownloader)

        val s = vm.state.value
        assertThat(s.whisperActiveModelName).isEqualTo(WhisperModelCatalog.default.displayName)
    }
}
