package com.opendash.app.voice.tts.voicevox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Manages on-device VOICEVOX assets that are NOT shipped in the APK:
 *   - OpenJTalk dictionary (~102MB, tar.gz from SourceForge, extracted to filesDir)
 *   - VVM voice models (50–100MB each, from the official voicevox_vvm GitHub release)
 *
 * This manager deliberately contains **no** references to
 * `jp.hiroshiba.voicevoxcore` — it only deals with files. Initialization of the
 * native synthesizer is the provider's responsibility.
 *
 * Stolen from openclaw-assistant's `VoiceVoxModelManager`, but the dictionary is
 * downloaded at runtime instead of shipped in assets (keeps the APK small).
 */
class VoiceVoxModelManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    companion object {
        const val DICT_DIR = "open_jtalk_dic_utf_8-1.11"

        /**
         * Canonical OpenJTalk dict tarball. SourceForge's JAIST mirror serves
         * the raw tarball without HTML redirects, which is important because
         * okhttp follows HTTP 3xx but does not parse HTML "click here to
         * download" interstitials.
         */
        private const val DICT_URL =
            "https://jaist.dl.sourceforge.net/project/open-jtalk/Dictionary/" +
                "open_jtalk_dic-1.11/open_jtalk_dic_utf_8-1.11.tar.gz"

        /** Official voicevox_vvm release for voicevox_core 0.16.4. */
        private const val VVM_BASE_URL =
            "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.3"

        private val VVM_FILES = mapOf(
            "0" to "$VVM_BASE_URL/0.vvm",
            "3" to "$VVM_BASE_URL/3.vvm",
            "4" to "$VVM_BASE_URL/4.vvm",
            "5" to "$VVM_BASE_URL/5.vvm",
            "9" to "$VVM_BASE_URL/9.vvm",
            "10" to "$VVM_BASE_URL/10.vvm",
            "15" to "$VVM_BASE_URL/15.vvm",
        )

        /** A fully-extracted dict contains `sys.dic`, which is the largest core file. */
        private const val DICT_MARKER_FILE = "sys.dic"

        /** Minimum plausible VVM size — incomplete downloads will be smaller. */
        private const val VVM_MIN_BYTES = 10_000_000L
    }

    // ── Dictionary ────────────────────────────────────────────────────────

    fun isDictionaryReady(): Boolean {
        val dictDir = File(context.filesDir, DICT_DIR)
        return dictDir.isDirectory && File(dictDir, DICT_MARKER_FILE).exists()
    }

    fun dictionaryPath(): String = File(context.filesDir, DICT_DIR).absolutePath

    /**
     * Download the OpenJTalk dict tarball and extract it into `filesDir/DICT_DIR`.
     * Emits coarse percentage progress. Network and disk I/O happen on
     * [Dispatchers.IO]; callers can collect on any dispatcher.
     */
    fun downloadAndExtractDictionary(): Flow<Progress> = flow {
        if (isDictionaryReady()) {
            emit(Progress.Success)
            return@flow
        }

        val destDir = File(context.filesDir, DICT_DIR)
        val tmpTar = File(context.cacheDir, "open_jtalk_dic.tar.gz")

        emit(Progress.Downloading(percent = 0))

        val request = Request.Builder().url(DICT_URL).get().build()
        var lastPct = 0
        val downloadOk = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("VOICEVOX dict download failed: HTTP ${response.code}")
                    false
                } else {
                    val body = response.body
                    if (body == null) {
                        false
                    } else {
                        val total = body.contentLength()
                        FileOutputStream(tmpTar).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var downloaded = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    if (total > 0) {
                                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            emit(Progress.Downloading(percent = pct))
                                        }
                                    }
                                }
                            }
                        }
                        true
                    }
                }
            }
        } catch (err: Throwable) {
            Timber.e(err, "VOICEVOX dict download threw")
            false
        }

        if (!downloadOk) {
            tmpTar.delete()
            emit(Progress.Error("Failed to download OpenJTalk dictionary"))
            return@flow
        }

        emit(Progress.Extracting)

        val extractOk = runCatching {
            destDir.deleteRecursively()
            destDir.mkdirs()
            GZIPInputStream(tmpTar.inputStream().buffered()).use { gz ->
                extractTar(gz, destDir)
            }
            isDictionaryReady()
        }.getOrElse { err ->
            Timber.e(err, "VOICEVOX dict extract failed")
            false
        }

        tmpTar.delete()

        if (extractOk) {
            emit(Progress.Success)
        } else {
            destDir.deleteRecursively()
            emit(Progress.Error("Failed to extract OpenJTalk dictionary"))
        }
    }.flowOn(Dispatchers.IO)

    // ── VVM models ────────────────────────────────────────────────────────

    fun vvmFile(vvmName: String): File = File(context.filesDir, "$vvmName.vvm")

    fun isVvmReady(vvmName: String): Boolean {
        val f = vvmFile(vvmName)
        return f.exists() && f.length() >= VVM_MIN_BYTES
    }

    fun downloadVvm(vvmName: String): Flow<Progress> = flow {
        val url = VVM_FILES[vvmName]
        if (url == null) {
            emit(Progress.Error("Unknown VVM: $vvmName"))
            return@flow
        }
        if (isVvmReady(vvmName)) {
            emit(Progress.Success)
            return@flow
        }

        emit(Progress.Downloading(percent = 0))

        val file = vvmFile(vvmName)
        val tmp = File(context.filesDir, "$vvmName.vvm.part")

        val request = Request.Builder().url(url).get().build()
        var lastPct = 0
        val ok = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("VVM download failed: $vvmName HTTP ${response.code}")
                    false
                } else {
                    val body = response.body
                    if (body == null) {
                        false
                    } else {
                        val total = body.contentLength()
                        FileOutputStream(tmp).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var downloaded = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    if (total > 0) {
                                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            emit(Progress.Downloading(percent = pct))
                                        }
                                    }
                                }
                            }
                        }
                        if (tmp.length() >= VVM_MIN_BYTES) {
                            tmp.renameTo(file)
                        } else {
                            false
                        }
                    }
                }
            }
        } catch (err: Throwable) {
            Timber.e(err, "VVM download threw: $vvmName")
            false
        }

        tmp.delete()
        if (ok) emit(Progress.Success) else emit(Progress.Error("Failed to download $vvmName.vvm"))
    }.flowOn(Dispatchers.IO)

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Minimal streaming tar (ustar) extractor. Supports the regular-file (`0`,
     * `\0`) and directory (`5`) typeflags, which is all the OpenJTalk dict
     * tarball uses. Long-name "L" entries (GNU extension) are tolerated by
     * skipping the oversized name and using it for the next entry.
     */
    private fun extractTar(input: InputStream, destDir: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null

        while (true) {
            val read = readFully(input, header)
            if (read < 512) break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker

            val rawName = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
            val name = pendingLongName ?: rawName
            pendingLongName = null

            val size = parseOctal(header, 124, 12)
            val typeflag = header[156].toInt().toChar()

            when (typeflag) {
                'L' -> {
                    // GNU long-name extension: next entry's real name is the body.
                    val bodySize = size.toInt()
                    val body = ByteArray(bodySize)
                    readFully(input, body)
                    pendingLongName = String(body, Charsets.US_ASCII).trimEnd('\u0000')
                    skipPadding(input, bodySize.toLong())
                }
                '5' -> {
                    File(destDir, stripLeadingDir(name)).mkdirs()
                }
                '0', '\u0000' -> {
                    val outFile = File(destDir, stripLeadingDir(name))
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        copyExactly(input, out, size)
                    }
                    skipPadding(input, size)
                }
                else -> {
                    // Skip unsupported entries (symlinks, etc.) — not used by
                    // the OpenJTalk dict tarball.
                    skipExactly(input, size)
                    skipPadding(input, size)
                }
            }
        }
    }

    /**
     * The upstream tarball stores files under `open_jtalk_dic_utf_8-1.11/` —
     * we already mkdir that directory ourselves, so drop the leading segment
     * when it matches, otherwise leave the path as-is.
     */
    private fun stripLeadingDir(name: String): String {
        val prefix = "$DICT_DIR/"
        return if (name.startsWith(prefix)) name.removePrefix(prefix) else name
    }

    private fun parseOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val s = String(buf, offset, len, Charsets.US_ASCII)
            .trim { it.isWhitespace() || it == '\u0000' }
        if (s.isEmpty()) return 0L
        return s.toLong(radix = 8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun copyExactly(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // Fall back to reading and discarding.
                val n = input.read()
                if (n < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    /** Tar entries are zero-padded to 512-byte boundaries. */
    private fun skipPadding(input: InputStream, size: Long) {
        val rem = (size % 512).toInt()
        if (rem != 0) skipExactly(input, (512 - rem).toLong())
    }

    sealed interface Progress {
        data class Downloading(val percent: Int) : Progress
        data object Extracting : Progress
        data object Success : Progress
        data class Error(val message: String) : Progress
    }
}
