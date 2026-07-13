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
 * `read_memory` is real: [SkillScriptContext.memory] is pre-fetched by
 * [com.opendash.app.assistant.skills.SkillToolExecutor] (scoped to the skill's own
 * `memory_keys` frontmatter, never the full store) and baked into the wrapped source
 * by [SkillScriptWrapper] — see its KDoc for why that works despite `evaluate()` being
 * fully synchronous with no live host callback. `call_tool` still has no bridge; see
 * [SkillScriptRuntime]'s KDoc for exactly why that one is a bigger, separate problem.
 */
@OptIn(EngineApi::class)
class QuickJsSkillScriptRuntime : SkillScriptRuntime {

    override fun isAvailable(): Boolean = true

    override suspend fun execute(
        script: SkillScript,
        context: SkillScriptContext
    ): SkillScriptResult = withContext(Dispatchers.Default) {
        val wrapped = SkillScriptWrapper.wrap(script.source, context.input, context.memory)
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
