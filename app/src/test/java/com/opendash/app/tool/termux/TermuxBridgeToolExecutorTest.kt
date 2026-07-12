package com.opendash.app.tool.termux

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.ToolCall
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TermuxBridgeToolExecutorTest {

    private lateinit var availability: ToggleableTermuxAvailability
    private lateinit var bridge: FakeTermuxBridge
    private lateinit var preferences: AppPreferences

    @BeforeEach
    fun setup() {
        availability = ToggleableTermuxAvailability()
        bridge = FakeTermuxBridge()
        preferences = mockk()
        // Default: both preferences unset (null -> treated as disabled / no allowlist)
        every { preferences.observe(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.TERMUX_COMMAND_ALLOWLIST) } returns flowOf(null)
    }

    private fun setPreferenceEnabled(value: Boolean?) {
        every { preferences.observe(PreferenceKeys.TERMUX_SHELL_EXECUTE_ENABLED) } returns flowOf(value)
    }

    private fun setAllowlist(csv: String?) {
        every { preferences.observe(PreferenceKeys.TERMUX_COMMAND_ALLOWLIST) } returns flowOf(csv)
    }

    private fun newExecutor() = TermuxBridgeToolExecutor(
        availability = availability,
        bridge = bridge,
        preferences = preferences
    )

    @Test
    fun `tool hidden when Termux not installed`() = runTest {
        availability.installed = false
        availability.permitted = true
        setPreferenceEnabled(true)

        assertThat(newExecutor().availableTools()).isEmpty()
    }

    @Test
    fun `tool hidden when preference unset`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(null)

        assertThat(newExecutor().availableTools()).isEmpty()
    }

    @Test
    fun `tool hidden when preference disabled`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(false)

        assertThat(newExecutor().availableTools()).isEmpty()
    }

    @Test
    fun `tool hidden when permission denied`() = runTest {
        availability.installed = true
        availability.permitted = false
        setPreferenceEnabled(true)

        assertThat(newExecutor().availableTools()).isEmpty()
    }

    @Test
    fun `tool description instructs the model to confirm with the user before running`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)

        val description = newExecutor().availableTools().single().description

        // Same prompt-level confirmation pattern as send_sms
        // (SmsToolExecutor) — this tool runs arbitrary shell commands, so
        // it needs at least as strong a confirmation instruction.
        assertThat(description).contains("confirmation")
    }

    @Test
    fun `tool exposed when all gates open`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)

        val tools = newExecutor().availableTools()

        assertThat(tools.map { it.name }).containsExactly("termux_shell_exec")
    }

    @Test
    fun `execute refuses when gate closed at call time`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(false)

        val result = newExecutor().execute(
            ToolCall(
                id = "x",
                name = "termux_shell_exec",
                arguments = mapOf("command" to "/bin/ls")
            )
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("disabled")
    }

    @Test
    fun `execute forwards command arguments to bridge`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        bridge.nextResult = TermuxResult.Success(stdout = "hello", stderr = "", exitCode = 0)

        val result = newExecutor().execute(
            ToolCall(
                id = "x",
                name = "termux_shell_exec",
                arguments = mapOf(
                    "command" to "/data/data/com.termux/files/usr/bin/echo",
                    "arguments" to listOf("hello"),
                    "working_dir" to "/sdcard",
                    "timeout_ms" to 5000
                )
            )
        )

        assertThat(result.success).isTrue()
        val req = bridge.lastRequest
        assertThat(req).isNotNull()
        assertThat(req?.command).isEqualTo("/data/data/com.termux/files/usr/bin/echo")
        assertThat(req?.arguments).containsExactly("hello")
        assertThat(req?.workingDir).isEqualTo("/sdcard")
        assertThat(req?.timeoutMs).isEqualTo(5000L)
    }

    @Test
    fun `execute serializes success output as JSON`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        bridge.nextResult = TermuxResult.Success(
            stdout = "line1\nline2",
            stderr = "warning",
            exitCode = 0
        )

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = mapOf("command" to "/bin/sh"))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("\"exit_code\":0")
        assertThat(result.data).contains("\"stdout\":\"line1\\nline2\"")
        assertThat(result.data).contains("\"stderr\":\"warning\"")
    }

    @Test
    fun `execute surfaces non-zero exit as non-success with exit code`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        bridge.nextResult = TermuxResult.Success(stdout = "", stderr = "boom", exitCode = 2)

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = mapOf("command" to "/bin/sh"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("exit=2")
    }

    @Test
    fun `execute surfaces timeout`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        bridge.nextResult = TermuxResult.Timeout(elapsedMs = 30_000)

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = mapOf("command" to "/bin/sleep"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("timed out")
    }

    @Test
    fun `execute rejects missing command`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = emptyMap())
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Missing command")
    }

    @Test
    fun `execute rejects unknown tool name`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "something_else", arguments = emptyMap())
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Unknown tool")
    }

    @Test
    fun `execute surfaces bridge failure`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        bridge.nextResult = TermuxResult.Failure("SecurityException: Termux refused call")

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = mapOf("command" to "/bin/sh"))
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("SecurityException")
    }

    @Test
    fun `empty allowlist disables the filter`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        setAllowlist("")
        bridge.nextResult = TermuxResult.Success("ok", "", 0)

        val result = newExecutor().execute(
            ToolCall(id = "x", name = "termux_shell_exec", arguments = mapOf("command" to "/bin/sh"))
        )

        assertThat(result.success).isTrue()
    }

    @Test
    fun `allowlist rejects commands not on the list`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        setAllowlist("/data/data/com.termux/files/usr/bin/git")

        val result = newExecutor().execute(
            ToolCall(
                id = "x", name = "termux_shell_exec",
                arguments = mapOf("command" to "/system/bin/rm")
            )
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("allowlist")
        assertThat(result.error).contains("/system/bin/rm")
        // Bridge should not have been called at all.
        assertThat(bridge.lastRequest).isNull()
    }

    @Test
    fun `allowlist permits commands on the list`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        setAllowlist(" /data/data/com.termux/files/usr/bin/git , /usr/bin/ls ")
        bridge.nextResult = TermuxResult.Success("ok", "", 0)

        val result = newExecutor().execute(
            ToolCall(
                id = "x", name = "termux_shell_exec",
                arguments = mapOf("command" to "/data/data/com.termux/files/usr/bin/git")
            )
        )

        assertThat(result.success).isTrue()
        assertThat(bridge.lastRequest?.command)
            .isEqualTo("/data/data/com.termux/files/usr/bin/git")
    }

    @Test
    fun `allowlist whitespace-only entries are ignored`() = runTest {
        availability.installed = true
        availability.permitted = true
        setPreferenceEnabled(true)
        // " ,  ,  " — all entries blank after trim, so the filter should be
        // inactive, same as an empty string.
        setAllowlist(" ,  ,  ")
        bridge.nextResult = TermuxResult.Success("ok", "", 0)

        val result = newExecutor().execute(
            ToolCall(
                id = "x", name = "termux_shell_exec",
                arguments = mapOf("command" to "/anything")
            )
        )

        assertThat(result.success).isTrue()
    }
}
