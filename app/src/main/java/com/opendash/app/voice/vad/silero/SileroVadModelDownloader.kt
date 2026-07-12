package com.opendash.app.voice.vad.silero

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads [SileroVadModelCatalog]'s single ONNX model with Range-resume
 * — same proven shape as [com.opendash.app.voice.stt.whisper.WhisperModelDownloader]
 * (P14.6) and [com.opendash.app.voice.wakeword.openwakeword.OpenWakeWordModelDownloader],
 * simplified to one file since Silero VAD ships as a single self-contained
 * ONNX graph (no separate feature-extraction model like openWakeWord).
 *
 * Storage layout: `filesDir/silero_vad/<SileroVadModelCatalog.FILENAME>`.
 */
class SileroVadModelDownloader(
    private val context: Context,
    private val modelUrl: String = SileroVadModelCatalog.URL,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {

    sealed class State {
        data object NotStarted : State()
        data class Downloading(val progress: Float, val downloadedMb: Int, val totalMb: Int) : State()
        data object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    private val vadDir: File
        get() = File(context.filesDir, "silero_vad").apply { mkdirs() }

    fun modelFile(): File = File(vadDir, SileroVadModelCatalog.FILENAME)

    fun isDownloaded(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() > 1024
    }

    fun deleteModel(): Boolean {
        val f = modelFile()
        val removed = f.exists() && f.delete()
        val tempRemoved = File(vadDir, "${SileroVadModelCatalog.FILENAME}.downloading").delete()
        if (removed || tempRemoved) _state.value = State.NotStarted
        return removed
    }

    suspend fun download(): State = withContext(Dispatchers.IO) {
        val target = modelFile()
        val temp = File(vadDir, "${SileroVadModelCatalog.FILENAME}.downloading")

        val resumeFrom = if (temp.exists()) temp.length() else 0L
        _state.value = State.Downloading(
            progress = 0f,
            downloadedMb = (resumeFrom / BYTES_PER_MB).toInt(),
            totalMb = (SileroVadModelCatalog.SIZE_BYTES / BYTES_PER_MB).toInt()
        )

        val conn = try {
            openConnection(URL(modelUrl))
        } catch (e: Exception) {
            Timber.w(e, "Failed to open connection for $modelUrl")
            return@withContext State.Error(e.message ?: "open connection failed").also { _state.value = it }
        }

        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "open-dash/1.0")
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        conn.instanceFollowRedirects = true

        try {
            val responseCode = conn.responseCode
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (responseCode != HttpURLConnection.HTTP_OK && !isPartial) {
                val err = State.Error("HTTP $responseCode")
                _state.value = err
                return@withContext err
            }

            val appendToExisting = isPartial && resumeFrom > 0
            val remainingBytes = conn.contentLengthLong
            val totalBytes = if (appendToExisting && remainingBytes > 0) remainingBytes + resumeFrom else remainingBytes
            val totalMb = if (totalBytes > 0) (totalBytes / BYTES_PER_MB).toInt() else (SileroVadModelCatalog.SIZE_BYTES / BYTES_PER_MB).toInt()
            var downloaded = if (appendToExisting) resumeFrom else 0L

            conn.inputStream.use { input ->
                FileOutputStream(temp, appendToExisting).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                        _state.value = State.Downloading(progress, (downloaded / BYTES_PER_MB).toInt(), totalMb)
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                val err = State.Error("Failed to rename ${temp.name} to ${target.name}")
                _state.value = err
                return@withContext err
            }
            Timber.d("Silero VAD model ready: ${target.name} (${target.length() / BYTES_PER_MB} MB)")
            State.Ready.also { _state.value = it }
        } catch (e: Exception) {
            Timber.w(e, "Silero VAD download failed (partial ${temp.length()} bytes kept for resume)")
            val err = State.Error(e.message ?: "download failed")
            _state.value = err
            err
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        private const val BYTES_PER_MB = 1_048_576L
    }
}
