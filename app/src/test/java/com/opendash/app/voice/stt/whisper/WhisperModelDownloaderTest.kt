package com.opendash.app.voice.stt.whisper

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class WhisperModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: WhisperModelDownloader

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("whisper-dl-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = WhisperModelDownloader(context)
    }

    @AfterEach
    fun teardown() {
        runCatching { server.shutdown() }
        tempDir.deleteRecursively()
    }

    private fun testModel(filename: String = "ggml-test.bin") = WhisperModel(
        id = "test",
        displayName = "Test Whisper",
        url = server.url("/m/$filename").toString(),
        filename = filename,
        sizeMb = 1,
        multilingual = true
    )

    private fun bodyOfSize(bytes: Int): Buffer {
        val buf = Buffer()
        repeat(bytes) { buf.writeByte(it and 0xFF) }
        return buf
    }

    @Test
    fun `fresh download writes file and reports Ready`() = runTest {
        val payload = bodyOfSize(2048)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "2048")
                .setBody(payload)
        )

        val model = testModel()
        val state = downloader.download(model)

        assertThat(state).isInstanceOf(WhisperModelDownloader.State.Ready::class.java)
        val file = downloader.modelFile(model)
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(2048L)
    }

    @Test
    fun `resume sends Range header and appends when server returns 206`() = runTest {
        val model = testModel("resume.bin")
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        val tempFile = File(whisperDir, "${model.filename}.downloading").apply {
            writeBytes(ByteArray(512) { (it and 0xFF).toByte() })
        }

        val remaining = bodyOfSize(512)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", "512")
                .setHeader("Content-Range", "bytes 512-1023/1024")
                .setBody(remaining)
        )

        val state = downloader.download(model)

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=512-")
        assertThat(state).isInstanceOf(WhisperModelDownloader.State.Ready::class.java)
        val finalFile = downloader.modelFile(model)
        assertThat(finalFile.length()).isEqualTo(1024L)
    }

    @Test
    fun `200 fallback truncates when server ignores Range`() = runTest {
        val model = testModel("fallback.bin")
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        File(whisperDir, "${model.filename}.downloading").writeBytes(ByteArray(300))

        val fullPayload = bodyOfSize(1000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1000")
                .setBody(fullPayload)
        )

        val state = downloader.download(model)

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=300-")
        assertThat(state).isInstanceOf(WhisperModelDownloader.State.Ready::class.java)
        val finalFile = downloader.modelFile(model)
        // Total length should match the 200 response, not 300 + 1000.
        assertThat(finalFile.length()).isEqualTo(1000L)
    }

    @Test
    fun `non-2xx response returns Error and keeps temp file`() = runTest {
        val model = testModel("fail.bin")
        val whisperDir = File(tempDir, "whisper").apply { mkdirs() }
        val tempFile = File(whisperDir, "${model.filename}.downloading").apply {
            writeBytes(ByteArray(256))
        }
        server.enqueue(MockResponse().setResponseCode(404))

        val state = downloader.download(model)

        assertThat(state).isInstanceOf(WhisperModelDownloader.State.Error::class.java)
        assertThat((state as WhisperModelDownloader.State.Error).message).contains("404")
        // Temp file kept so a future call can retry via Range.
        assertThat(tempFile.exists()).isTrue()
    }

    @Test
    fun `isDownloaded reflects renamed target file`() = runTest {
        val model = testModel("present.bin")
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "4096").setBody(bodyOfSize(4096))
        )
        assertThat(downloader.isDownloaded(model)).isFalse()

        downloader.download(model)

        assertThat(downloader.isDownloaded(model)).isTrue()
    }

    @Test
    fun `deleteModel removes file and resets state`() = runTest {
        val model = testModel("delete-me.bin")
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "2048").setBody(bodyOfSize(2048))
        )
        downloader.download(model)
        assertThat(downloader.isDownloaded(model)).isTrue()

        assertThat(downloader.deleteModel(model)).isTrue()
        assertThat(downloader.isDownloaded(model)).isFalse()
        assertThat(downloader.state.value).isEqualTo(WhisperModelDownloader.State.NotStarted)
    }
}
