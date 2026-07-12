package com.opendash.app.tool.termux

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import kotlinx.coroutines.flow.first

/**
 * P19.2: exposes `termux_shell_exec` to the LLM, gated behind BOTH the
 * Termux capability probe and the user's opt-in preference.
 *
 * Gating contract:
 *
 *  - `availableTools()` returns an empty list unless
 *    [TermuxAvailability.isAvailable] AND
 *    [PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED] == true.
 *
 *  - Flipping either gate off at runtime removes the tool from the LLM's
 *    visible tool set on the next turn — the model cannot reference a
 *    capability it was never shown.
 *
 *  - Direct `execute()` calls still re-check both gates, so even a stale
 *    tool_call that races with a toggle cannot slip through.
 */
class TermuxBridgeToolExecutor(
    private val availability: TermuxAvailability,
    private val bridge: TermuxBridge,
    private val preferences: AppPreferences
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> {
        if (!isGatedOn()) return emptyList()

        return listOf(
            ToolSchema(
                name = TOOL_NAME,
                description = "Run a shell command through the user's Termux app. " +
                    "Advanced power-user only; the user has explicitly enabled " +
                    "this in Settings and understands that commands execute on-device. " +
                    "Requires explicit user confirmation before using — describe exactly " +
                    "what the command will do and wait for the user to say yes before " +
                    "calling this tool, the same as send_sms. " +
                    "Prefer other tools when available; only use this when no first-class tool fits.",
                parameters = mapOf(
                    "command" to ToolParameter(
                        type = "string",
                        description = "Absolute path to the binary to run, e.g. /data/data/com.termux/files/usr/bin/bash",
                        required = true
                    ),
                    "arguments" to ToolParameter(
                        type = "array",
                        description = "Optional command arguments (strings)",
                        required = false
                    ),
                    "working_dir" to ToolParameter(
                        type = "string",
                        description = "Optional working directory",
                        required = false
                    ),
                    "timeout_ms" to ToolParameter(
                        type = "integer",
                        description = "Optional timeout in milliseconds (default 30000)",
                        required = false
                    )
                )
            )
        )
    }

    override suspend fun execute(call: ToolCall): ToolResult {
        if (call.name != TOOL_NAME) {
            return ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
        if (!isGatedOn()) {
            return ToolResult(
                call.id, false, "",
                "Termux bridge is disabled. Install Termux and enable it in Settings → Advanced."
            )
        }

        val command = call.arguments["command"] as? String
            ?: return ToolResult(call.id, false, "", "Missing command parameter")

        val allowlist = readAllowlist()
        if (allowlist.isNotEmpty() && command !in allowlist) {
            return ToolResult(
                call.id, false, "",
                "Command '$command' is not on the Termux allowlist configured in Settings. " +
                    "Allowed commands: ${allowlist.joinToString(", ")}"
            )
        }

        val arguments = when (val raw = call.arguments["arguments"]) {
            null -> emptyList()
            is List<*> -> raw.filterIsInstance<String>()
            is Array<*> -> raw.filterIsInstance<String>()
            else -> return ToolResult(call.id, false, "", "arguments must be a string array")
        }

        val workingDir = call.arguments["working_dir"] as? String
        val timeoutMs = (call.arguments["timeout_ms"] as? Number)?.toLong()
            ?: TermuxRequest.DEFAULT_TIMEOUT_MS

        val request = TermuxRequest(
            command = command,
            arguments = arguments,
            workingDir = workingDir,
            timeoutMs = timeoutMs
        )

        return when (val result = bridge.exec(request)) {
            is TermuxResult.Success -> ToolResult(
                call.id, result.exitCode == 0,
                """{"exit_code":${result.exitCode},"stdout":"${result.stdout.escapeJson()}","stderr":"${result.stderr.escapeJson()}"}""",
                if (result.exitCode == 0) null else "exit=${result.exitCode}"
            )
            is TermuxResult.Failure -> ToolResult(call.id, false, "", result.reason)
            is TermuxResult.NotAvailable -> ToolResult(call.id, false, "", result.reason)
            is TermuxResult.Timeout -> ToolResult(
                call.id, false, "",
                "Termux command timed out after ${result.elapsedMs}ms"
            )
        }
    }

    private suspend fun isGatedOn(): Boolean {
        if (!availability.isAvailable()) return false
        val enabled = preferences.observe(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED).first()
        return enabled == true
    }

    /**
     * Parse the comma-separated [PreferenceKeys.TERMUX_COMMAND_ALLOWLIST].
     * Blank / empty entries are dropped. Returns an empty list when the
     * preference is unset OR contains only whitespace — in that state the
     * executor skips the allowlist check entirely.
     */
    private suspend fun readAllowlist(): List<String> {
        val raw = preferences.observe(PreferenceKeys.TERMUX_COMMAND_ALLOWLIST).first()
            ?: return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        const val TOOL_NAME = "termux_shell_exec"
    }
}
