package com.opendash.app.voice.stt.whisper

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
 * Phase 16 item 5: download whisper.cpp GGML models with Range-resume
 * support, mirroring [com.opendash.app.assistant.provider.embedded.ModelDownloader]'s
 * proven shape (P14.6).
 *
 * State transitions: NotStarted → Downloading → Ready | Error.
 *
 * Storage layout: `filesDir/whisper/<model.filename>` — matches the
 * default `modelPathProvider` in `SttModule.buildWhisperDelegate`, so a
 * successful download immediately makes `WhisperSttProvider` operational
 * (subject to the native library being present).
 *
 * Resume: a partial `<filename>.downloading` file from a prior failed
 * run is re-opened and `Range: bytes=N-` is sent. HTTP 206 means the
 * server honoured it and we append; HTTP 200 means the server restarted
 * from zero and we truncate. Any other response is an error and the
 * temp file is kept so the next call can try again.
 */
class WhisperModelDownloader(
    private val context: Context,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {

    sealed class State {
        data object NotStarted : State()
        data class Downloading(
            val progress: Float,
            val downloadedMb: Int,
            val totalMb: Int
        ) : State()
        data object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    private val whisperDir: File
        get() = File(context.filesDir, "whisper").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(whisperDir, model.filename)

    fun isDownloaded(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() > 1024
    }

    fun deleteModel(model: WhisperModel): Boolean {
        val f = modelFile(model)
        val removed = f.exists() && f.delete()
        val tempRemoved = File(whisperDir, "${model.filename}.downloading").delete()
        if (removed || tempRemoved) {
            _state.value = State.NotStarted
        }
        return removed
    }

    suspend fun download(model: WhisperModel): State = withContext(Dispatchers.IO) {
        val target = modelFile(model)
        val temp = File(whisperDir, "${model.filename}.downloading")

        val resumeFrom = if (temp.exists()) temp.length() else 0L
        _state.value = State.Downloading(
            progress = 0f,
            downloadedMb = (resumeFrom / BYTES_PER_MB).toInt(),
            totalMb = model.sizeMb
        )
        if (resumeFrom > 0) {
            Timber.d("Resuming whisper model download: ${model.displayName} from byte $resumeFrom")
        } else {
            Timber.d("Downloading whisper model: ${model.displayName} from ${model.url}")
        }

        val conn = try {
            openConnection(URL(model.url))
        } catch (e: Exception) {
            Timber.w(e, "Failed to open connection for ${model.url}")
            return@withContext State.Error(e.message ?: "open connection failed")
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
                val err = State.Error("HTTP $responseCode")
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
            val totalMb = if (totalBytes > 0) (totalBytes / BYTES_PER_MB).toInt() else model.sizeMb
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
                            progress = progress,
                            downloadedMb = (downloaded / BYTES_PER_MB).toInt(),
                            totalMb = totalMb
                        )
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                val err = State.Error("Failed to rename ${temp.name} to ${target.name}")
                _state.value = err
                return@withContext err
            }
            Timber.d("Whisper model ready: ${target.name} (${target.length() / BYTES_PER_MB} MB)")
            State.Ready.also { _state.value = it }
        } catch (e: Exception) {
            Timber.w(e, "whisper download failed (partial ${temp.length()} bytes kept for resume)")
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
