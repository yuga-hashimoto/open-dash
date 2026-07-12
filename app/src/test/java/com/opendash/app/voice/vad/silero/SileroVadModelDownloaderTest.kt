package com.opendash.app.voice.vad.silero

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
import java.net.URL
import java.nio.file.Files

class SileroVadModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: SileroVadModelDownloader

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("silero-dl-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = SileroVadModelDownloader(context, modelUrl = server.url("/m/model.onnx").toString())
    }

    @AfterEach
    fun teardown() {
        runCatching { server.shutdown() }
        tempDir.deleteRecursively()
    }

    private fun bodyOfSize(bytes: Int): Buffer {
        val buf = Buffer()
        repeat(bytes) { buf.writeByte(it and 0xFF) }
        return buf
    }

    @Test
    fun `fresh download writes file and reports Ready`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "2048").setBody(bodyOfSize(2048))
        )

        val state = downloader.download()

        assertThat(state).isInstanceOf(SileroVadModelDownloader.State.Ready::class.java)
        assertThat(downloader.modelFile().exists()).isTrue()
        assertThat(downloader.modelFile().length()).isEqualTo(2048L)
    }

    @Test
    fun `isDownloaded reflects renamed target file`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "2048").setBody(bodyOfSize(2048))
        )
        assertThat(downloader.isDownloaded()).isFalse()

        downloader.download()

        assertThat(downloader.isDownloaded()).isTrue()
    }

    @Test
    fun `resume sends Range header and appends when server returns 206`() = runTest {
        val vadDir = File(tempDir, "silero_vad").apply { mkdirs() }
        File(vadDir, "${SileroVadModelCatalog.FILENAME}.downloading")
            .writeBytes(ByteArray(512) { (it and 0xFF).toByte() })

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", "512")
                .setHeader("Content-Range", "bytes 512-1023/1024")
                .setBody(bodyOfSize(512))
        )

        val state = downloader.download()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=512-")
        assertThat(state).isInstanceOf(SileroVadModelDownloader.State.Ready::class.java)
        assertThat(downloader.modelFile().length()).isEqualTo(1024L)
    }

    @Test
    fun `non-2xx response returns Error and keeps temp file`() = runTest {
        val vadDir = File(tempDir, "silero_vad").apply { mkdirs() }
        val tempFile = File(vadDir, "${SileroVadModelCatalog.FILENAME}.downloading").apply {
            writeBytes(ByteArray(256))
        }
        server.enqueue(MockResponse().setResponseCode(404))

        val state = downloader.download()

        assertThat(state).isInstanceOf(SileroVadModelDownloader.State.Error::class.java)
        assertThat(tempFile.exists()).isTrue()
    }

    @Test
    fun `deleteModel removes file and resets state`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "2048").setBody(bodyOfSize(2048))
        )
        downloader.download()
        assertThat(downloader.isDownloaded()).isTrue()

        assertThat(downloader.deleteModel()).isTrue()

        assertThat(downloader.isDownloaded()).isFalse()
        assertThat(downloader.state.value).isEqualTo(SileroVadModelDownloader.State.NotStarted)
    }
}
