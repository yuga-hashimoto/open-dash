package com.opendash.app.tool.info

import com.opendash.app.tool.ToolCall
import com.opendash.app.tool.ToolExecutor
import com.opendash.app.tool.ToolParameter
import com.opendash.app.tool.ToolResult
import com.opendash.app.tool.ToolSchema
import timber.log.Timber

/**
 * Sports scores, via [SportsScoreProvider] (ESPN's public site API in
 * production, no key required). Stock prices and traffic (the other
 * two items in this same roadmap gap) are not implemented — both
 * typically require an API key even on their free tiers, unlike
 * sports scores, and were skipped per the original plan's own stated
 * condition rather than force an external-service dependency the user
 * hasn't specifically approved.
 */
class SportsToolExecutor(
    private val provider: SportsScoreProvider
) : ToolExecutor {

    override suspend fun availableTools(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "get_sports_scores",
            description = "Get today's scores for a sports league. Supported leagues: nba, nfl, mlb, nhl, " +
                "premier_league, champions_league, college_football, college_basketball.",
            parameters = mapOf(
                "league" to ToolParameter("string", "League key, e.g. 'nba'", required = true),
                "team" to ToolParameter("string", "Optional team name to filter to a single game", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = try {
        when (call.name) {
            "get_sports_scores" -> executeScores(call)
            else -> ToolResult(call.id, false, "", "Unknown tool: ${call.name}")
        }
    } catch (e: Exception) {
        Timber.e(e, "Sports tool failed")
        ToolResult(call.id, false, "", e.message ?: "Sports lookup failed")
    }

    private suspend fun executeScores(call: ToolCall): ToolResult {
        val league = call.arguments["league"] as? String
            ?: return ToolResult(call.id, false, "", "Missing league")
        val team = (call.arguments["team"] as? String)?.trim()

        var games = provider.getScores(league)
        if (!team.isNullOrEmpty()) {
            games = games.filter {
                it.homeTeam.contains(team, ignoreCase = true) || it.awayTeam.contains(team, ignoreCase = true)
            }
        }

        val data = games.joinToString(",") { g ->
            """{"home_team":"${g.homeTeam.escapeJson()}","home_score":${g.homeScore ?: "null"},"away_team":"${g.awayTeam.escapeJson()}","away_score":${g.awayScore ?: "null"},"status":"${g.status.escapeJson()}"}"""
        }
        return ToolResult(call.id, true, "[$data]")
    }

    private fun String.escapeJson(): String = buildString(length) {
        for (c in this@escapeJson) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
