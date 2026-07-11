package com.opendash.app.tool.info

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The fixture below mirrors the real shape of ESPN's public scoreboard
 * endpoint (verified live via `GET
 * https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard`
 * during implementation — `score` is a JSON string, not a number).
 */
class EspnSportsScoreProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: EspnSportsScoreProvider
    private val moshi = Moshi.Builder().build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = EspnSportsScoreProvider(
            client = OkHttpClient(),
            moshi = moshi,
            baseUrl = server.url("/sports").toString().removeSuffix("/")
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private val fixtureJson = """
        {
          "events": [
            {
              "competitions": [
                {
                  "status": { "type": { "description": "Final" } },
                  "competitors": [
                    { "homeAway": "home", "score": "90", "team": { "displayName": "San Antonio Spurs" } },
                    { "homeAway": "away", "score": "94", "team": { "displayName": "New York Knicks" } }
                  ]
                }
              ]
            },
            {
              "competitions": [
                {
                  "status": { "type": { "description": "In Progress" } },
                  "competitors": [
                    { "homeAway": "home", "score": "50", "team": { "displayName": "Los Angeles Lakers" } },
                    { "homeAway": "away", "score": "48", "team": { "displayName": "Boston Celtics" } }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `getScores parses real ESPN scoreboard shape`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureJson).setResponseCode(200))

        val games = provider.getScores("nba")

        assertThat(games).hasSize(2)
        assertThat(games[0]).isEqualTo(GameScore("San Antonio Spurs", 90, "New York Knicks", 94, "Final"))
        assertThat(games[1]).isEqualTo(GameScore("Los Angeles Lakers", 50, "Boston Celtics", 48, "In Progress"))
    }

    @Test
    fun `getScores requests the correct league path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"events":[]}""").setResponseCode(200))

        provider.getScores("nba")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/sports/basketball/nba/scoreboard")
    }

    @Test
    fun `getScores unknown league throws before making a request`() = runTest {
        try {
            provider.getScores("curling")
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("curling")
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `getScores non-2xx response throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            provider.getScores("nba")
            error("expected exception")
        } catch (e: RuntimeException) {
            assertThat(e.message).contains("500")
        }
    }

    @Test
    fun `getScores skips events with missing competitor data`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"events":[{"competitions":[{"competitors":[{"homeAway":"home","score":"1","team":{"displayName":"Only Home"}}]}]}]}"""
            ).setResponseCode(200)
        )

        val games = provider.getScores("nba")

        assertThat(games).isEmpty()
    }
}
