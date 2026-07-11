package com.opendash.app.tool.info

/**
 * A single game/match score, home vs. away.
 */
data class GameScore(
    val homeTeam: String,
    val homeScore: Int?,
    val awayTeam: String,
    val awayScore: Int?,
    /** e.g. "Final", "In Progress", "Scheduled". */
    val status: String
)

interface SportsScoreProvider {
    /** @throws IllegalArgumentException if [league] isn't a recognized key. */
    suspend fun getScores(league: String): List<GameScore>
}
