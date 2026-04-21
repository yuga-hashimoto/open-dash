package com.opendash.app.voice.stt.whisper

import com.google.common.truth.Truth.assertThat
import com.opendash.app.voice.stt.SttResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperSttProviderTest {

    @TempDir
    lateinit var tempDir: File

    private fun existingModel(): File = File(tempDir, "ggml-tiny.bin").apply {
        writeBytes(ByteArray(64) { 0x1 })
    }

    private fun provider(
        bridge: WhisperCppBridge = FakeReadyBridge(),
        pcm: WhisperPcmSource = FakeWhisperPcmSource(FloatArray(16_000) { 0.5f }),
        modelPath: () -> File = { existingModel() },
        available: Boolean = true
    ) = WhisperSttProvider(
        bridge = bridge,
        pcmSource = pcm,
        modelPathProvider = modelPath,
        availabilityCheck = { available }
    )

    @Test
    fun `emits Error when native library unavailable`() = runTest {
        val result = provider(available = false).startListening().first()
        assertThat(result).isInstanceOf(SttResult.Error::class.java)
        assertThat((result as SttResult.Error).message).contains("native library is not available")
    }

    @Test
    fun `emits Error when model file is missing`() = runTest {
        val missing = File(tempDir, "never-downloaded.bin")
        val result = provider(modelPath = { missing }).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Error::class.java)
        assertThat((result as SttResult.Error).message).contains("not downloaded")
    }

    @Test
    fun `emits Error when loadModel fails`() = runTest {
        val result = provider(
            bridge = FakeReadyBridge(loadResult = false)
        ).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Error::class.java)
        assertThat((result as SttResult.Error).message).contains("failed to load model")
    }

    @Test
    fun `emits Final text on success`() = runTest {
        val result = provider(
            bridge = FakeReadyBridge(transcribeResult = "  hello world  ")
        ).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Final::class.java)
        assertThat((result as SttResult.Final).text).isEqualTo("hello world")
    }

    @Test
    fun `empty capture yields empty Final with zero confidence`() = runTest {
        val result = provider(
            pcm = FakeWhisperPcmSource(FloatArray(0))
        ).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Final::class.java)
        assertThat((result as SttResult.Final).text).isEmpty()
        assertThat(result.confidence).isEqualTo(0f)
    }

    @Test
    fun `SecurityException from PCM source surfaces as microphone Error`() = runTest {
        val pcm = FakeWhisperPcmSource(FloatArray(0), throwOnCapture = SecurityException("no perm"))
        val result = provider(pcm = pcm).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Error::class.java)
        assertThat((result as SttResult.Error).message).contains("Microphone permission")
    }

    @Test
    fun `transcribe exception surfaces as Error`() = runTest {
        val bridge = FakeReadyBridge(throwOnTranscribe = RuntimeException("boom"))
        val result = provider(bridge = bridge).startListening().first()

        assertThat(result).isInstanceOf(SttResult.Error::class.java)
        assertThat((result as SttResult.Error).message).contains("boom")
    }

    @Test
    fun `stopListening calls pcm source stop`() = runTest {
        val pcm = FakeWhisperPcmSource(FloatArray(16_000))
        val p = provider(pcm = pcm)
        p.stopListening()
        assertThat(pcm.stopCallCount).isEqualTo(1)
        assertThat(p.isListening.value).isFalse()
    }

    @Test
    fun `successful flow emits exactly one Final and completes`() = runTest {
        val results = provider(
            bridge = FakeReadyBridge(transcribeResult = "ok")
        ).startListening().toList()

        assertThat(results).hasSize(1)
        assertThat(results[0]).isInstanceOf(SttResult.Final::class.java)
    }

    private class FakeReadyBridge(
        private val transcribeResult: String = "",
        private val loadResult: Boolean = true,
        private val throwOnTranscribe: Exception? = null
    ) : WhisperCppBridge() {
        private var loaded = false
        override fun loadModel(path: String): Boolean {
            loaded = loadResult
            return loadResult
        }
        override fun isModelLoaded(): Boolean = loaded
        override fun transcribe(samples: FloatArray, language: String, translate: Boolean): String {
            throwOnTranscribe?.let { throw it }
            check(loaded) { "whisper model not loaded" }
            return transcribeResult
        }
    }
}
