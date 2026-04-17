package com.opensmarthome.speaker.voice.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

object TtsUtils {

    private val REGEX_CODE_BLOCK_START = Regex("```.*\\n?")
    private val REGEX_CODE_BLOCK_END = Regex("```")
    private val REGEX_HEADER = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val REGEX_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val REGEX_ITALIC = Regex("\\*([^*]+)\\*")
    private val REGEX_INLINE_CODE = Regex("`([^`]+)`")
    private val REGEX_LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val REGEX_IMAGE = Regex("!\\[([^\\]]*)]\\([^)]+\\)")
    private val REGEX_HR = Regex("^[-*_]{3,}$", RegexOption.MULTILINE)
    private val REGEX_BLOCKQUOTE = Regex("^>\\s?", RegexOption.MULTILINE)
    private val REGEX_BULLET = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE)
    private val REGEX_NEWLINES = Regex("\n{3,}")

    // Split-point candidates ordered by priority. Paragraph and line breaks are
    // handled separately; these two lists back the sentence-end and comma
    // passes of findBestSplitPoint.
    private val SENTENCE_ENDERS = listOf("。", "．", ". ", "! ", "? ", "！", "？")
    private val COMMA_ENDERS = listOf("、", "，", ", ")

    // Fallback used when TextToSpeech.getMaxSpeechInputLength() cannot be
    // queried (e.g. in JVM unit tests without an Android framework on the
    // classpath). Matches openclaw-assistant's conservative default.
    private const val DEFAULT_MAX_INPUT_LENGTH = 3900

    fun stripMarkdownForSpeech(text: String): String {
        var result = text
        result = result.replace("<end_of_turn>", "")
        result = result.replace(REGEX_CODE_BLOCK_START, "")
        result = result.replace(REGEX_CODE_BLOCK_END, "")
        result = result.replace(REGEX_HEADER, "")
        result = result.replace(REGEX_BOLD, "$1")
        result = result.replace(REGEX_ITALIC, "$1")
        result = result.replace(REGEX_INLINE_CODE, "$1")
        // Images must be stripped BEFORE links, otherwise REGEX_LINK matches
        // the `[alt](url)` portion of `![alt](url)` and leaves a stray `!`.
        result = result.replace(REGEX_IMAGE, "$1")
        result = result.replace(REGEX_LINK, "$1")
        result = result.replace(REGEX_HR, "")
        result = result.replace(REGEX_BLOCKQUOTE, "")
        result = result.replace(REGEX_BULLET, "")
        result = result.replace(REGEX_NEWLINES, "\n\n")
        return result.trim()
    }

    /**
     * Split a long string into chunks no longer than [maxChars].
     *
     * The splitter walks the text greedily, at each step trying to keep as
     * much content as possible up to [maxChars] while cutting at the best
     * available boundary. Boundary priority:
     *
     *   1. Paragraph break (`\n\n`) — found past `length/2`
     *   2. Sentence end (`. ! ? 。 ！ ？ ．`) — found past `length/3`
     *   3. Line break (`\n`) — found past `length/3`
     *   4. Comma (`、 ， ,`) — found past `length/3`
     *   5. Whitespace — found past `length/3`
     *   6. Force-cut at [maxChars]
     *
     * Blank chunks (produced when the input has runs of whitespace) are
     * filtered out so they never reach `TextToSpeech.speak()`.
     *
     * Android TTS degrades or silently truncates on very long utterances.
     * Breaking at natural boundaries keeps prosody clean and prevents the
     * engine from dropping the tail of long Japanese answers.
     *
     * Reference: openclaw-assistant `TTSUtils.splitTextForTTS` +
     * `findBestSplitPoint`.
     */
    fun splitIntoChunks(text: String, maxChars: Int = 500): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        if (text.length <= maxChars) {
            return if (text.isBlank()) emptyList() else listOf(text.trim())
        }

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                chunks += remaining
                break
            }

            val searchRange = remaining.substring(0, maxChars)
            val splitIndex = findBestSplitPoint(searchRange)

            if (splitIndex > 0) {
                chunks += remaining.substring(0, splitIndex).trim()
                remaining = remaining.substring(splitIndex).trim()
            } else {
                // No natural boundary: force-cut at maxChars. Do NOT trim
                // the cut segment — callers of `joinToString("")` expect
                // force-cut chunks to reassemble into the original input.
                chunks += remaining.substring(0, maxChars)
                remaining = remaining.substring(maxChars)
            }
        }

        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Find the best split position in [text] using openclaw's priority rules.
     *
     * Returns -1 when no suitable boundary exists; callers should force-cut
     * the text in that case. Indices returned are exclusive end positions
     * in `text` (safe to use as `substring(0, index)` cut points).
     */
    private fun findBestSplitPoint(text: String): Int {
        // Paragraph break gets the most room: only require it past the
        // midpoint so we still batch multiple paragraphs together when they
        // are small.
        val paragraphBreak = text.lastIndexOf("\n\n")
        if (paragraphBreak > text.length / 2) return paragraphBreak + 2

        val oneThird = text.length / 3

        var bestPos = -1
        for (ender in SENTENCE_ENDERS) {
            val pos = text.lastIndexOf(ender)
            if (pos >= 0) {
                val endIdx = pos + ender.length
                if (endIdx > bestPos) bestPos = endIdx
            }
        }
        if (bestPos > oneThird) return bestPos

        val lineBreak = text.lastIndexOf('\n')
        if (lineBreak > oneThird) return lineBreak + 1

        bestPos = -1
        for (ender in COMMA_ENDERS) {
            val pos = text.lastIndexOf(ender)
            if (pos >= 0) {
                val endIdx = pos + ender.length
                if (endIdx > bestPos) bestPos = endIdx
            }
        }
        if (bestPos > oneThird) return bestPos

        val space = text.lastIndexOf(' ')
        if (space > oneThird) return space + 1

        return -1
    }

    /**
     * Query the engine's actual max input length with a safe margin.
     *
     * Uses 9/10 of `TextToSpeech.getMaxSpeechInputLength()` (typically 4000,
     * so ~3600 chars) to leave headroom for UTF-16 expansion of multibyte
     * characters and internal padding inside the TTS engine. Falls back to
     * a conservative default when the platform query is unavailable (unit
     * tests) or throws.
     */
    fun getMaxInputLength(tts: TextToSpeech?): Int {
        return try {
            val limit = TextToSpeech.getMaxSpeechInputLength()
            if (limit <= 0) {
                DEFAULT_MAX_INPUT_LENGTH
            } else {
                (limit * 9 / 10).coerceAtLeast(500)
            }
        } catch (e: Throwable) {
            // Android framework classes are not available in plain JVM
            // unit tests; also guards against engines that return 0.
            // `tts` is accepted for future extensibility but intentionally
            // unused here since getMaxSpeechInputLength is a static.
            @Suppress("UNUSED_VARIABLE")
            val unused = tts
            DEFAULT_MAX_INPUT_LENGTH
        }
    }

    data class EngineInfo(
        val packageName: String,
        val label: String
    )

    fun getAvailableEngines(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val services = pm.queryIntentServices(intent, 0)
        return services.map { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo
            val label = serviceInfo.applicationInfo.loadLabel(pm).toString()
            EngineInfo(
                packageName = serviceInfo.packageName,
                label = label
            )
        }.distinctBy { it.packageName }
    }
}
