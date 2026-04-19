package com.opendash.app.tool.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import timber.log.Timber

/**
 * Places a phone call.
 *
 * Prefers `ACTION_CALL` (direct dial — matches the "Alexa, call Bob"
 * flow) when the runtime `CALL_PHONE` permission has been granted.
 * Falls back to `ACTION_DIAL` (dialer pre-populated, user taps to call)
 * when the permission is absent, so the tool is still useful before
 * the user has granted calling permission.
 *
 * Accepts either a raw [phone_number] or a [name] to look up via the
 * existing [ContactsProvider]. When [name] resolves to multiple contacts
 * with phone numbers, the first match is used and the rest are listed in
 * the tool result so the LLM can surface them if needed.
 */
class PhoneCallToolExecutor(
    private val context: Context,
    private val contactsProvider: ContactsProvider,
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "make_call",
            description = "Place a phone call. Two-step verbal confirmation is required — " +
                "NEVER call this with confirmed=true until the user has said yes in the current " +
                "conversation. Step 1: call with confirmed=false (or omitted). You will get back " +
                "a confirmation prompt — repeat it to the user and WAIT for their reply. Step 2: " +
                "if the user agrees (はい / yes / OK / いいよ / そう), call again with the same " +
                "name/phone_number AND confirmed=true — the call is placed immediately. If the " +
                "user refuses, apologise briefly and do not re-call this tool.",
            parameters = mapOf(
                "contact_name" to ToolParameter(
                    type = "string",
                    description = "Contact person's name to look up (e.g. \"橋本優里\"). Optional if phone_number is given. Do NOT put the tool name here.",
                    required = false,
                ),
                "phone_number" to ToolParameter(
                    type = "string",
                    description = "Raw phone number to dial (e.g. \"0901234567\"). Optional if contact_name is given.",
                    required = false,
                ),
                "confirmed" to ToolParameter(
                    type = "boolean",
                    description = "Set to true ONLY after the user has verbally confirmed in this " +
                        "conversation that they want to place the call. Defaults to false, which " +
                        "returns a prompt instead of placing the call.",
                    required = false,
                ),
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != "make_call") {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        val rawPhone = (call.arguments["phone_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        // Accept both "contact_name" (new) and "name" (legacy) for forward
        // compatibility with models that were trained on the old schema.
        // Reject the literal tool name so a confused LLM that writes
        // {"name":"make_call"} doesn't trigger a surname search for
        // "make_call".
        val rawName = (call.arguments["contact_name"] as? String)
            ?: (call.arguments["name"] as? String)
        val name = rawName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("make_call", ignoreCase = true) }
        val confirmed = when (val raw = call.arguments["confirmed"]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            is Number -> raw.toInt() != 0
            else -> false
        }
        Timber.d("make_call args: name='$name' phone='$rawPhone' confirmed=$confirmed")
        Timber.d("make_call contacts permission granted=${contactsProvider.hasPermission()}")

        val (phone, resolvedName, alternatives) = resolveTarget(name, rawPhone)
            ?: run {
                Timber.w("make_call: neither name nor phone provided")
                return ToolResult(
                    call.id,
                    false,
                    "",
                    "Neither name nor phone_number was usable. Pass at least one."
                )
            }

        if (phone == null) {
            Timber.w("make_call: contact lookup failed for name='$name' (resolved='$resolvedName', alternatives=$alternatives)")
            return ToolResult(
                call.id,
                false,
                "",
                "Could not find a phone number for \"$name\". Ask the user to confirm the name."
            )
        }
        Timber.d("make_call: resolved name='$resolvedName' phone='$phone' alternatives=$alternatives")

        // Confirmation gate. Without explicit confirmed=true we never place
        // the call — instead we return a prompt the LLM is instructed to
        // speak to the user, along with the exact arguments to re-call
        // this tool with once the user says yes.
        if (!confirmed) {
            val display = resolvedName ?: phone
            val prompt = "${display}さんに電話をかけてよろしいですか？"
            return ToolResult(
                call.id,
                true,
                """{"pending_confirmation":true,"ask_user":"${prompt.escapeJson()}",""" +
                    """"resolved_name":"${(resolvedName ?: "").escapeJson()}",""" +
                    """"resolved_phone":"$phone",""" +
                    """"next_call_arguments":{"name":"${(name ?: resolvedName ?: "").escapeJson()}",""" +
                    """"phone_number":"$phone","confirmed":true}}"""
            )
        }

        // We only get here when confirmed=true, meaning VoicePipeline
        // has already relayed the "○○さんに電話してよろしいですか?"
        // prompt and the user said "はい" (see ConfirmCallMatcher +
        // VoicePipeline's pendingCall handling). Place the call
        // directly via ACTION_CALL so the device is fully voice-driven
        // — the user never has to tap the dialer screen.
        val canPlaceCall = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (canPlaceCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val dialUri = Uri.fromParts("tel", phone, null)
        val intent = Intent(action, dialUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val display = resolvedName ?: phone
            val mode = if (canPlaceCall) "direct" else "dialer"
            ToolResult(
                call.id,
                true,
                """{"dialed":"$phone","name":"${display.escapeJson()}","mode":"$mode","alternatives":${alternatives.joinToString(",", "[", "]") { "\"" + it.escapeJson() + "\"" }}}"""
            )
        } catch (e: SecurityException) {
            // ACTION_CALL surprisingly rejected (e.g. default phone app
            // lock). Retry via the dialer so the user can still connect.
            Timber.w(e, "ACTION_CALL rejected, falling back to ACTION_DIAL")
            runCatching {
                val fallback = Intent(Intent.ACTION_DIAL, dialUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                ToolResult(
                    call.id,
                    true,
                    """{"dialed":"$phone","name":"${(resolvedName ?: phone).escapeJson()}","mode":"dialer"}"""
                )
            }.getOrElse {
                ToolResult(call.id, false, "", it.message ?: "Dialer launch failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch dialer")
            ToolResult(call.id, false, "", e.message ?: "Dialer launch failed")
        }
    }

    private suspend fun resolveTarget(
        name: String?,
        phoneOverride: String?,
    ): Triple<String?, String?, List<String>>? {
        if (phoneOverride != null) {
            return Triple(phoneOverride, name, emptyList())
        }
        if (name == null) return null

        if (!contactsProvider.hasPermission()) {
            return Triple(null, name, emptyList())
        }
        val matches = smartSearch(name)
        val withPhones = matches.filter { it.phoneNumbers.isNotEmpty() }
        val primary = withPhones.firstOrNull()
            ?: return Triple(null, name, matches.map { it.displayName })
        val phone = primary.phoneNumbers.first()
        val alternatives = withPhones.drop(1).map { it.displayName }
        return Triple(phone, primary.displayName, alternatives)
    }

    /**
     * Contact lookup with a Japanese STT fallback.
     *
     * Direct `LIKE '%full query%'` often misses because Vosk/Google
     * transcribe names as "橋本ゆうり" (kanji+hiragana) while the actual
     * address-book entry is "橋本優里" (kanji+kanji). The fallback
     * splits on script boundaries (kanji / hiragana / katakana / other)
     * and re-queries each token; a short kanji surname ("橋本") then
     * hits the real contact even when the given name transcription is
     * wrong.
     */
    private suspend fun smartSearch(query: String): List<com.opendash.app.tool.system.ContactInfo> {
        val trimmed = query.trim().trim('さ', '様', '君', 'ち', 'ゃ', 'ん').ifEmpty { query.trim() }
        Timber.d("smartSearch: input='$query' trimmed='$trimmed'")

        // 1. Exact LIKE %full query% first.
        val directAll = contactsProvider.search(trimmed, limit = 10)
        val direct = directAll.filter { it.phoneNumbers.isNotEmpty() }
        Timber.d("smartSearch[direct '$trimmed']: total=${directAll.size}, with-phone=${direct.size}")
        if (direct.isNotEmpty()) return direct

        // 2. Script-boundary split ("橋本ゆうり" → ["橋本", "ゆうり"]).
        val tokens = splitByScript(trimmed).distinct()
        Timber.d("smartSearch[split]: tokens=$tokens")
        val tokenHits = tokens
            .flatMap { contactsProvider.search(it, limit = 5) }
            .filter { it.phoneNumbers.isNotEmpty() }
            .distinctBy { it.id }
            .take(5)
        Timber.d("smartSearch[tokens]: hits=${tokenHits.size}")
        if (tokenHits.isNotEmpty()) return tokenHits

        // 3. Surname guess.
        val leadingKanji = trimmed.takeWhile { it in '\u4E00'..'\u9FFF' || it == '\u3005' }
        if (leadingKanji.length >= 2) {
            val surname = leadingKanji.take(2)
            val surnameAll = contactsProvider.search(surname, limit = 10)
            val surnameHits = surnameAll.filter { it.phoneNumbers.isNotEmpty() }
            Timber.d("smartSearch[surname '$surname']: total=${surnameAll.size}, with-phone=${surnameHits.size}")
            if (surnameHits.isNotEmpty()) return surnameHits
        }

        // 4. Last-ditch: first single kanji character.
        val firstKanji = trimmed.firstOrNull { it in '\u4E00'..'\u9FFF' }?.toString()
        if (firstKanji != null) {
            val broadAll = contactsProvider.search(firstKanji, limit = 20)
            val broad = broadAll.filter { it.phoneNumbers.isNotEmpty() }
            Timber.d("smartSearch[first-kanji '$firstKanji']: total=${broadAll.size}, with-phone=${broad.size}")
            if (broad.isNotEmpty()) return broad
        }

        Timber.w("smartSearch: no hits for '$trimmed' via any strategy")
        return emptyList()
    }

    private fun splitByScript(s: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var lastScript = -1
        for (c in s) {
            val script = when {
                c in '\u4E00'..'\u9FFF' || c == '\u3005' -> 1 // kanji
                c in '\u3041'..'\u309F' -> 2                   // hiragana
                c in '\u30A0'..'\u30FF' -> 3                   // katakana
                c.isLetter() -> 4                              // latin
                else -> 0
            }
            if (script == 0) continue
            if (lastScript != -1 && script != lastScript && current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            }
            current.append(c)
            lastScript = script
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.filter { it.length >= 2 }
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
