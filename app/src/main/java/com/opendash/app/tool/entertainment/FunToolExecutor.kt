package com.opendash.app.tool.entertainment

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import com.opendash.app.tool.escapeJson
import timber.log.Timber
import kotlin.random.Random

/**
 * Lightweight entertainment tools — jokes, trivia, fun facts — sampled
 * from [BundledFunContent]. Fills the "ask Alexa/Google to tell you a
 * joke" gap; this app previously only had `flip_coin`/`roll_dice`/
 * `pick_random` for playful requests.
 *
 * Seeded via a caller-provided [random] so tests can pin the output,
 * matching [com.opendash.app.tool.info.RandomToolExecutor]'s convention.
 */
class FunToolExecutor(
    private val random: Random = Random.Default
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "tell_joke",
            description = "Tell a short, clean joke.",
            parameters = emptyMap()
        ),
        ToolSchema(
            name = "get_trivia",
            description = "Ask a trivia question and reveal its answer.",
            parameters = emptyMap()
        ),
        ToolSchema(
            name = "fun_fact",
            description = "Share a random fun fact.",
            parameters = emptyMap()
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "tell_joke" -> tellJoke(call)
            "get_trivia" -> getTrivia(call)
            "fun_fact" -> funFact(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Fun tool failed")
        ToolResult(call.id, false, "", e.message ?: "Fun tool failed")
    }

    private fun tellJoke(call: ToolCall): ToolResult {
        val joke = BundledFunContent.JOKES[random.nextInt(BundledFunContent.JOKES.size)]
        return ToolResult(call.id, true, """{"joke":"${joke.escapeJson()}"}""")
    }

    private fun getTrivia(call: ToolCall): ToolResult {
        val trivia = BundledFunContent.TRIVIA[random.nextInt(BundledFunContent.TRIVIA.size)]
        return ToolResult(
            call.id,
            true,
            """{"question":"${trivia.question.escapeJson()}","answer":"${trivia.answer.escapeJson()}"}"""
        )
    }

    private fun funFact(call: ToolCall): ToolResult {
        val fact = BundledFunContent.FACTS[random.nextInt(BundledFunContent.FACTS.size)]
        return ToolResult(call.id, true, """{"fact":"${fact.escapeJson()}"}""")
    }
}
