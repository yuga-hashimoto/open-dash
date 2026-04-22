package com.opendash.app.tool.cache

import kotlin.time.Duration.Companion.minutes

/**
 * Default TTLs for read-only tools that show up in smart-speaker
 * follow-ups. Chosen so back-to-back questions (weather → "will I need a
 * coat?") skip the network while staying fresh enough that the answer
 * isn't stale.
 *
 * Absent tools bypass the cache entirely. Any mutation tool
 * (set_timer, execute_command, send_sms, reply_to_notification...) is
 * intentionally absent.
 */
object DefaultCachePolicy {

    val INSTANCE = CachePolicy(
        ttls = mapOf(
            // Weather & forecast: follow-ups rarely need minute-fresh data;
            // 5 min balances freshness vs. the typical conversational window.
            "get_weather" to 5.minutes,
            "get_forecast" to 15.minutes,

            // News headlines update at aggregator cadence — longer TTL fine.
            "get_news" to 10.minutes,

            // Web search / fetch — identical query usually means follow-up.
            "web_search" to 10.minutes,
            "web_fetch" to 10.minutes,

            // Knowledge base lookups don't change within a conversation.
            "search_knowledge" to 30.minutes,

            // Currency rates update on the hour, not by the minute.
            "convert_currency" to 10.minutes,
            "list_currencies" to 60.minutes
        )
    )
}
