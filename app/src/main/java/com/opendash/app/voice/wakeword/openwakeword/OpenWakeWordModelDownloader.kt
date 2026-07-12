package com.opendash.app.voice.wakeword.openwakeword

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
 * Downloads the three ONNX files [OpenWakeWordModelCatalog] needs, with
 * per-file Range-resume — same shape as
 * [com.opendash.app.voice.stt.whisper.WhisperModelDownloader] /
 * [com.opendash.app.voice.tts.piper.PiperVoiceDownloader] (P14.6's
 * proven pattern), generalized to a set of files instead of one
 * user-selectable model since openWakeWord needs all three
 * (melspectrogram + embedding + classifier) before it can run at all.
 *
 * Storage layout: `filesDir/openwakeword/<file.filename>`.
 */
class OpenWakeWordModelDownloader(
    private val context: Context,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {

    sealed class State {
        data object NotStarted : State()
        data class Downloading(
            val fileId: String,
            val progress: Float,
            val downloadedMb: Int,
            val totalMb: Int
        ) : State()
        data object Ready : State()
        data class Error(val fileId: String, val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    private val modelDir: File
        get() = File(context.filesDir, "openwakeword").apply { mkdirs() }

    fun file(model: OpenWakeWordModelFile): File = File(modelDir, model.filename)

    fun isDownloaded(model: OpenWakeWordModelFile): Boolean {
        val f = file(model)
        return f.exists() && f.length() == model.sizeBytes
    }

    fun allDownloaded(models: List<OpenWakeWordModelFile> = OpenWakeWordModelCatalog.all): Boolean =
        models.all { isDownloaded(it) }

    fun deleteAll(models: List<OpenWakeWordModelFile> = OpenWakeWordModelCatalog.all) {
        models.forEach { model ->
            file(model).delete()
            File(modelDir, "${model.filename}.downloading").delete()
        }
        _state.value = State.NotStarted
    }

    /**
     * Downloads every file in [models] not already present, in order,
     * stopping at the first failure. Defaults to the real catalog;
     * tests inject a MockWebServer-backed list instead.
     */
    suspend fun downloadAll(models: List<OpenWakeWordModelFile> = OpenWakeWordModelCatalog.all): State {
        for (model in models) {
            if (isDownloaded(model)) continue
            val result = downloadOne(model)
            if (result is State.Error) return result
        }
        return State.Ready.also { _state.value = it }
    }

    private suspend fun downloadOne(model: OpenWakeWordModelFile): State = withContext(Dispatchers.IO) {
        val target = file(model)
        val temp = File(modelDir, "${model.filename}.downloading")

        val resumeFrom = if (temp.exists()) temp.length() else 0L
        _state.value = State.Downloading(
            fileId = model.id,
            progress = 0f,
            downloadedMb = (resumeFrom / BYTES_PER_MB).toInt(),
            totalMb = (model.sizeBytes / BYTES_PER_MB).toInt()
        )

        val conn = try {
            openConnection(URL(model.url))
        } catch (e: Exception) {
            Timber.w(e, "Failed to open connection for ${model.url}")
            return@withContext State.Error(model.id, e.message ?: "open connection failed")
                .also { _state.value = it }
        }

        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "open-dash/1.0")
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        conn.instanceFollowRedirects = true

        try {
            val responseCode = conn.responseCode
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (responseCode != HttpURLConnection.HTTP_OK && !isPartial) {
                val err = State.Error(model.id, "HTTP $responseCode")
                _state.value = err
                return@withContext err
            }

            val appendToExisting = isPartial && resumeFrom > 0
            val remainingBytes = conn.contentLengthLong
            val totalBytes = if (appendToExisting && remainingBytes > 0) {
                remainingBytes + resumeFrom
            } else {
                remainingBytes
            }
            val totalMb = if (totalBytes > 0) (totalBytes / BYTES_PER_MB).toInt() else (model.sizeBytes / BYTES_PER_MB).toInt()
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
                        _state.value = State.Downloading(
                            fileId = model.id,
                            progress = progress,
                            downloadedMb = (downloaded / BYTES_PER_MB).toInt(),
                            totalMb = totalMb
                        )
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                val err = State.Error(model.id, "Failed to rename ${temp.name} to ${target.name}")
                _state.value = err
                return@withContext err
            }
            Timber.d("openWakeWord model ready: ${target.name} (${target.length() / BYTES_PER_MB} MB)")
            State.Ready
        } catch (e: Exception) {
            Timber.w(e, "openWakeWord download failed for ${model.id} (partial ${temp.length()} bytes kept for resume)")
            val err = State.Error(model.id, e.message ?: "download failed")
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
