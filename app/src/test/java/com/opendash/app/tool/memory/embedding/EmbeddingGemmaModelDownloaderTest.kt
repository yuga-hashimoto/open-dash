package com.opendash.app.tool.memory.embedding

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

class EmbeddingGemmaModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: EmbeddingGemmaModelDownloader

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("embgemma-dl-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = EmbeddingGemmaModelDownloader(context, modelUrl = server.url("/m/model.task").toString())
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
            MockResponse().setResponseCode(200).setHeader("Content-Length", "4096").setBody(bodyOfSize(4096))
        )

        val state = downloader.download()

        assertThat(state).isInstanceOf(EmbeddingGemmaModelDownloader.State.Ready::class.java)
        assertThat(downloader.modelFile().exists()).isTrue()
        assertThat(downloader.modelFile().length()).isEqualTo(4096L)
    }

    @Test
    fun `isDownloaded reflects renamed target file`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "4096").setBody(bodyOfSize(4096))
        )
        assertThat(downloader.isDownloaded()).isFalse()

        downloader.download()

        assertThat(downloader.isDownloaded()).isTrue()
    }

    @Test
    fun `resume sends Range header and appends when server returns 206`() = runTest {
        val dir = File(tempDir, "embedding_gemma").apply { mkdirs() }
        File(dir, "${EmbeddingGemmaModelCatalog.FILENAME}.downloading")
            .writeBytes(ByteArray(2048) { (it and 0xFF).toByte() })

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", "2048")
                .setHeader("Content-Range", "bytes 2048-4095/4096")
                .setBody(bodyOfSize(2048))
        )

        val state = downloader.download()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=2048-")
        assertThat(state).isInstanceOf(EmbeddingGemmaModelDownloader.State.Ready::class.java)
        assertThat(downloader.modelFile().length()).isEqualTo(4096L)
    }

    @Test
    fun `non-2xx response returns Error and keeps temp file`() = runTest {
        val dir = File(tempDir, "embedding_gemma").apply { mkdirs() }
        val tempFile = File(dir, "${EmbeddingGemmaModelCatalog.FILENAME}.downloading").apply {
            writeBytes(ByteArray(256))
        }
        server.enqueue(MockResponse().setResponseCode(503))

        val state = downloader.download()

        assertThat(state).isInstanceOf(EmbeddingGemmaModelDownloader.State.Error::class.java)
        assertThat(tempFile.exists()).isTrue()
    }

    @Test
    fun `deleteModel removes file and resets state`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "4096").setBody(bodyOfSize(4096))
        )
        downloader.download()
        assertThat(downloader.isDownloaded()).isTrue()

        assertThat(downloader.deleteModel()).isTrue()

        assertThat(downloader.isDownloaded()).isFalse()
        assertThat(downloader.state.value).isEqualTo(EmbeddingGemmaModelDownloader.State.NotStarted)
    }
}
