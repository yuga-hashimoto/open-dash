package com.opendash.app.assistant.skills.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class StubSkillScriptRuntimeTest {

    private val runtime = StubSkillScriptRuntime()

    @Test
    fun `isAvailable is false`() {
        assertThat(runtime.isAvailable()).isFalse()
    }

    @Test
    fun `execute returns NotAvailable with actionable reason`() = runTest {
        val script = SkillScript("s", 0, "js", "return 1;")
        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.NotAvailable::class.java)
        val reason = (result as SkillScriptResult.NotAvailable).reason
        assertThat(reason).contains("not installed")
    }
}
