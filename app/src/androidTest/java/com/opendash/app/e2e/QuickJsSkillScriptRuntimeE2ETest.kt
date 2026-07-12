package com.opendash.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.skills.runtime.QuickJsSkillScriptRuntime
import com.opendash.app.assistant.skills.runtime.SkillScript
import com.opendash.app.assistant.skills.runtime.SkillScriptContext
import com.opendash.app.assistant.skills.runtime.SkillScriptResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real QuickJS native library (P19.1) — the unit tests under
 * `app/src/test/...` only cover [com.opendash.app.assistant.skills.runtime.SkillScriptWrapper]'s
 * pure string-building logic, since `QuickJs.create()` loads an Android-ABI
 * `.so` that a plain JVM unit test process cannot load (it needs ART/an NDK
 * dynamic linker, not just a matching CPU architecture).
 *
 * Not yet run in this environment — no device/emulator was available to
 * execute instrumented tests during implementation. Written now so CI (which
 * does have emulators) exercises it on the next run.
 */
@RunWith(AndroidJUnit4::class)
class QuickJsSkillScriptRuntimeE2ETest {

    private val runtime = QuickJsSkillScriptRuntime()

    @Test
    fun isAvailable_reportsTrue() {
        assertThat(runtime.isAvailable()).isTrue()
    }

    @Test
    fun execute_returnsStringResultOfSimpleExpression() = runBlocking {
        val script = SkillScript(skillName = "test", index = 0, language = "js", source = "return 1 + 1;")

        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.Success::class.java)
        assertThat((result as SkillScriptResult.Success).output).isEqualTo("2")
    }

    @Test
    fun execute_echoesInputBackToTheScript() = runBlocking {
        val script = SkillScript(skillName = "test", index = 0, language = "js", source = "return input.toUpperCase();")

        val result = runtime.execute(script, SkillScriptContext(input = "hello"))

        assertThat(result).isInstanceOf(SkillScriptResult.Success::class.java)
        assertThat((result as SkillScriptResult.Success).output).isEqualTo("HELLO")
    }

    @Test
    fun execute_stringifiesObjectReturns() = runBlocking {
        val script = SkillScript(
            skillName = "test",
            index = 0,
            language = "js",
            source = "return { ok: true, n: 3 };"
        )

        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.Success::class.java)
        assertThat((result as SkillScriptResult.Success).output).isEqualTo("{\"ok\":true,\"n\":3}")
    }

    @Test
    fun execute_hasNoFilesystemOrNetworkAccess() = runBlocking {
        val script = SkillScript(
            skillName = "test",
            index = 0,
            language = "js",
            source = "return typeof require + ',' + typeof fetch + ',' + typeof XMLHttpRequest;"
        )

        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.Success::class.java)
        assertThat((result as SkillScriptResult.Success).output).isEqualTo("undefined,undefined,undefined")
    }

    @Test
    fun execute_infiniteLoopIsInterruptedRatherThanHangingForever() = runBlocking {
        val script = SkillScript(
            skillName = "test",
            index = 0,
            language = "js",
            source = "while (true) {}"
        )

        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.Failure::class.java)
    }

    @Test
    fun execute_syntaxErrorReturnsFailureNotACrash() = runBlocking {
        val script = SkillScript(skillName = "test", index = 0, language = "js", source = "this is not valid js (((")

        val result = runtime.execute(script, SkillScriptContext())

        assertThat(result).isInstanceOf(SkillScriptResult.Failure::class.java)
    }
}
