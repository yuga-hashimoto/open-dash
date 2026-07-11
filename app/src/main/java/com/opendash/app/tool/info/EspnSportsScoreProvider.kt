package com.opendash.app.tool.info

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sports scores via ESPN's public site API (no key required — the same
 * endpoint ESPN's own website/app calls). Undocumented but widely used
 * and stable in practice; if ESPN ever locks it down, [getScores] will
 * start throwing and this provider degrades to a tool-level error
 * rather than a crash.
 */
class EspnSportsScoreProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String = DEFAULT_BASE_URL
) : SportsScoreProvider {

    override suspend fun getScores(league: String): List<GameScore> = withContext(Dispatchers.IO) {
        val path = LEAGUE_PATHS[league.lowercase()]
            ?: throw IllegalArgumentException("Unknown league: $league")

        val request = Request.Builder().url("$baseUrl/$path/scoreboard").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Sports API error: ${response.code}")
            }
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            parseScoreboard(body)
        }
    }

    private fun parseScoreboard(json: String): List<GameScore> {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?>
            ?: throw RuntimeException("Invalid sports response")
        val events = root["events"] as? List<*> ?: emptyList<Any?>()

        return events.mapNotNull { event ->
            val eventMap = event as? Map<*, *> ?: return@mapNotNull null
            val competitions = eventMap["competitions"] as? List<*> ?: return@mapNotNull null
            val competition = competitions.firstOrNull() as? Map<*, *> ?: return@mapNotNull null
            val competitors = competition["competitors"] as? List<*> ?: return@mapNotNull null
            val home = competitors.firstOrNull { (it as? Map<*, *>)?.get("homeAway") == "home" } as? Map<*, *>
                ?: return@mapNotNull null
            val away = competitors.firstOrNull { (it as? Map<*, *>)?.get("homeAway") == "away" } as? Map<*, *>
                ?: return@mapNotNull null
            val statusType = (competition["status"] as? Map<*, *>)?.get("type") as? Map<*, *>

            GameScore(
                homeTeam = teamName(home),
                homeScore = (home["score"] as? String)?.toIntOrNull(),
                awayTeam = teamName(away),
                awayScore = (away["score"] as? String)?.toIntOrNull(),
                status = statusType?.get("description") as? String ?: "Unknown"
            )
        }
    }

    private fun teamName(competitor: Map<*, *>): String =
        (competitor["team"] as? Map<*, *>)?.get("displayName") as? String ?: "Unknown"

    companion object {
        const val DEFAULT_BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"

        /** ESPN's sport/league path segments for the leagues this tool supports. */
        val LEAGUE_PATHS: Map<String, String> = mapOf(
            "nba" to "basketball/nba",
            "nfl" to "football/nfl",
            "mlb" to "baseball/mlb",
            "nhl" to "hockey/nhl",
            "premier_league" to "soccer/eng.1",
            "champions_league" to "soccer/uefa.champions",
            "college_football" to "football/college-football",
            "college_basketball" to "basketball/mens-college-basketball"
        )
    }
}
