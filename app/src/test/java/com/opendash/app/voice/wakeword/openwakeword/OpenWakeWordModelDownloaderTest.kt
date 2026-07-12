package com.opendash.app.voice.wakeword.openwakeword

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
import android.content.Context

class OpenWakeWordModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var downloader: OpenWakeWordModelDownloader

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("oww-dl-").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        downloader = OpenWakeWordModelDownloader(context)
    }

    @AfterEach
    fun teardown() {
        runCatching { server.shutdown() }
        tempDir.deleteRecursively()
    }

    private fun testFile(id: String, filename: String, sizeBytes: Long) = OpenWakeWordModelFile(
        id = id,
        url = server.url("/m/$filename").toString(),
        filename = filename,
        sha256 = "unused-in-tests",
        sizeBytes = sizeBytes
    )

    private fun bodyOfSize(bytes: Int): Buffer {
        val buf = Buffer()
        repeat(bytes) { buf.writeByte(it and 0xFF) }
        return buf
    }

    @Test
    fun `downloadAll fetches every file and reports Ready`() = runTest {
        val a = testFile("a", "a.onnx", 1024)
        val b = testFile("b", "b.onnx", 512)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "1024").setBody(bodyOfSize(1024)))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "512").setBody(bodyOfSize(512)))

        val state = downloader.downloadAll(listOf(a, b))

        assertThat(state).isInstanceOf(OpenWakeWordModelDownloader.State.Ready::class.java)
        assertThat(downloader.file(a).length()).isEqualTo(1024L)
        assertThat(downloader.file(b).length()).isEqualTo(512L)
        assertThat(downloader.allDownloaded(listOf(a, b))).isTrue()
    }

    @Test
    fun `downloadAll skips files already downloaded`() = runTest {
        val a = testFile("a", "a.onnx", 1024)
        // Pre-place the target file so it's already "downloaded".
        val owwDir = File(tempDir, "openwakeword").apply { mkdirs() }
        File(owwDir, "a.onnx").writeBytes(ByteArray(1024))

        val state = downloader.downloadAll(listOf(a))

        assertThat(state).isInstanceOf(OpenWakeWordModelDownloader.State.Ready::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `downloadAll stops at the first failure and does not fetch later files`() = runTest {
        val a = testFile("a", "a.onnx", 1024)
        val b = testFile("b", "b.onnx", 512)
        server.enqueue(MockResponse().setResponseCode(404))

        val state = downloader.downloadAll(listOf(a, b))

        assertThat(state).isInstanceOf(OpenWakeWordModelDownloader.State.Error::class.java)
        assertThat((state as OpenWakeWordModelDownloader.State.Error).fileId).isEqualTo("a")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `resume sends Range header and appends when server returns 206`() = runTest {
        val model = testFile("resume", "resume.onnx", 1024)
        val owwDir = File(tempDir, "openwakeword").apply { mkdirs() }
        File(owwDir, "${model.filename}.downloading").writeBytes(ByteArray(512) { (it and 0xFF).toByte() })

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", "512")
                .setHeader("Content-Range", "bytes 512-1023/1024")
                .setBody(bodyOfSize(512))
        )

        val state = downloader.downloadAll(listOf(model))

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Range")).isEqualTo("bytes=512-")
        assertThat(state).isInstanceOf(OpenWakeWordModelDownloader.State.Ready::class.java)
        assertThat(downloader.file(model).length()).isEqualTo(1024L)
    }

    @Test
    fun `deleteAll removes files and resets state`() = runTest {
        val a = testFile("a", "a.onnx", 1024)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "1024").setBody(bodyOfSize(1024)))
        downloader.downloadAll(listOf(a))
        assertThat(downloader.allDownloaded(listOf(a))).isTrue()

        downloader.deleteAll(listOf(a))

        assertThat(downloader.allDownloaded(listOf(a))).isFalse()
        assertThat(downloader.state.value).isEqualTo(OpenWakeWordModelDownloader.State.NotStarted)
    }
}
