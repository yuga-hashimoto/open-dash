package com.opendash.app.assistant.skills.runtime

import app.cash.zipline.EngineApi
import app.cash.zipline.InterruptHandler
import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sandboxes a [SkillScript] in a fresh QuickJS context per call (cashapp/zipline's
 * `QuickJs` low-level `evaluate()`, not the full `Zipline`/`ZiplineLoader` stack —
 * that machinery is for loading precompiled Kotlin/JS bundles, a different use case
 * from evaluating arbitrary raw JS text pulled out of a SKILL.md body).
 *
 * fs/net/process lockdown is automatic: bare `QuickJs.evaluate()` exposes only the
 * ECMAScript standard library — no `require`, `fetch`, `XMLHttpRequest`, or any other
 * ambient host binding — since we never call `initOutboundChannel`/bind any host
 * object into the context. Nothing needs to be explicitly denied because nothing is
 * exposed in the first place.
 *
 * Deliberately NOT implemented in this pass: a `call_tool`/`read_memory` bridge back
 * into the app. Building that means exposing a real capability surface to
 * script-authored code — a materially different, security-sensitive piece of work
 * from "run this JS and get a string back" — so it's left for a dedicated follow-up
 * rather than bolted on here. Every script sees only [SkillScriptContext.input].
 */
@OptIn(EngineApi::class)
class QuickJsSkillScriptRuntime : SkillScriptRuntime {

    override fun isAvailable(): Boolean = true

    override suspend fun execute(
        script: SkillScript,
        context: SkillScriptContext
    ): SkillScriptResult = withContext(Dispatchers.Default) {
        val wrapped = SkillScriptWrapper.wrap(script.source, context.input)
        val deadlineNanos = System.nanoTime() + TIMEOUT_MS * 1_000_000L

        val quickJs = try {
            QuickJs.create()
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Failed to create QuickJs context for ${script.skillName}#${script.index}")
            return@withContext SkillScriptResult.Failure("Could not start the script sandbox")
        }

        try {
            quickJs.memoryLimit = MEMORY_LIMIT_BYTES
            quickJs.maxStackSize = MAX_STACK_SIZE_BYTES
            quickJs.interruptHandler = InterruptHandler { System.nanoTime() > deadlineNanos }

            val result = quickJs.evaluate(wrapped, fileName = "${script.skillName}#${script.index}.js")
            SkillScriptResult.Success(result as? String ?: result?.toString().orEmpty())
        } catch (e: QuickJsException) {
            Timber.w(e, "Skill script failed: ${script.skillName}#${script.index}")
            SkillScriptResult.Failure(e.message ?: "Script execution failed")
        } finally {
            quickJs.close()
        }
    }

    private companion object {
        /** Scripts are short-lived helpers, not long-running jobs — 5s is generous. */
        const val TIMEOUT_MS = 5_000L
        const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
        const val MAX_STACK_SIZE_BYTES = 512L * 1024
    }
}
