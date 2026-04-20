package com.opendash.app.tool.info

import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Web search via the Brave Search API
 * (https://api.search.brave.com/res/v1/web/search).
 *
 * Brave returns an LLM-friendly JSON SERP — clean titles + meaningful
 * snippets + the original publisher URL — without the broker-ad noise that
 * dominates the DuckDuckGo HTML scraper for Japanese / time-sensitive
 * queries.
 *
 * The user supplies their own API key (free tier is 2,000 queries/month at
 * the time of writing) via Settings → Search → Brave API key. When the key
 * is unset this provider throws so [SearchProviderChain] cleanly falls
 * back to the DuckDuckGo providers — there is no implicit network call.
 *
 * Endpoint docs: https://brave.com/search/api/
 */
class BraveSearchProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val securePreferences: SecurePreferences,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
) : SearchProvider {

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val apiKey = securePreferences.getString(SecurePreferences.KEY_BRAVE_SEARCH_API_KEY)
        if (apiKey.isBlank()) {
            throw IllegalStateException("Brave Search API key not configured")
        }

        val url = endpoint.toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            addQueryParameter("count", maxResults.toString())
            addQueryParameter("safesearch", "moderate")
        }.build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("X-Subscription-Token", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Brave Search API error: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            parseResult(query, body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResult(query: String, json: String): SearchResult {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?>
            ?: throw RuntimeException("Brave Search: invalid response")

        val web = root["web"] as? Map<String, Any?>
        val results = (web?.get("results") as? List<Map<String, Any?>>).orEmpty()

        if (results.isEmpty()) {
            Timber.d("Brave Search returned 0 web results for query='$query'")
            return SearchResult(query = query, abstract = "", sourceUrl = null, relatedTopics = emptyList())
        }

        // The first result becomes the abstract + sourceUrl; the rest
        // populate relatedTopics so consumers (LLM polish, ToolResultSummarizer)
        // see a compact title list to choose follow-ups from.
        val top = results.first()
        val topTitle = (top["title"] as? String).orEmpty()
        val topDescription = (top["description"] as? String).orEmpty()
        val topUrl = (top["url"] as? String)?.takeIf { it.isNotBlank() }

        val abstract = listOf(topTitle, topDescription)
            .filter { it.isNotBlank() }
            .joinToString(" — ")

        val related = results.drop(1)
            .mapNotNull { r ->
                val title = (r["title"] as? String).orEmpty().trim()
                val desc = (r["description"] as? String).orEmpty().trim()
                when {
                    title.isNotBlank() && desc.isNotBlank() -> "$title — $desc"
                    title.isNotBlank() -> title
                    desc.isNotBlank() -> desc
                    else -> null
                }
            }
            .take(maxResults - 1)

        return SearchResult(
            query = query,
            abstract = abstract,
            sourceUrl = topUrl,
            relatedTopics = related,
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        const val DEFAULT_MAX_RESULTS = 6
    }
}
