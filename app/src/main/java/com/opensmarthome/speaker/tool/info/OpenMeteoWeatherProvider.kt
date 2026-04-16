package com.opensmarthome.speaker.tool.info

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder

/**
 * Weather provider using Open-Meteo API (free, no auth required).
 * Docs: https://open-meteo.com/en/docs
 */
class OpenMeteoWeatherProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi
) : WeatherProvider {

    companion object {
        private const val GEO_API = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FORECAST_API = "https://api.open-meteo.com/v1/forecast"
    }

    override suspend fun getCurrent(location: String?): WeatherInfo {
        val coords = resolveCoordinates(location)
        val url = FORECAST_API.toHttpUrl().newBuilder().apply {
            addQueryParameter("latitude", coords.latitude.toString())
            addQueryParameter("longitude", coords.longitude.toString())
            addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
            addQueryParameter("timezone", "auto")
        }.build()

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Weather API error: ${response.code}")
            }
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            return parseCurrentWeather(body, coords.name)
        }
    }

    override suspend fun getForecast(location: String?, days: Int): List<DayForecast> {
        val coords = resolveCoordinates(location)
        val url = FORECAST_API.toHttpUrl().newBuilder().apply {
            addQueryParameter("latitude", coords.latitude.toString())
            addQueryParameter("longitude", coords.longitude.toString())
            addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,weather_code")
            addQueryParameter("forecast_days", days.toString())
            addQueryParameter("timezone", "auto")
        }.build()

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Forecast API error: ${response.code}")
            }
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            return parseForecast(body)
        }
    }

    private data class Coords(val latitude: Double, val longitude: Double, val name: String)

    private fun resolveCoordinates(location: String?): Coords {
        // If no location provided, default to Tokyo (could be enhanced with device location)
        val query = location?.takeIf { it.isNotBlank() } ?: "Tokyo"
        val url = "$GEO_API?name=${URLEncoder.encode(query, "UTF-8")}&count=1&language=en".toHttpUrl()
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Geocoding error: ${response.code}")
            }
            val body = response.body?.string() ?: throw RuntimeException("Empty geocoding response")
            return parseGeocoding(body, query)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseGeocoding(json: String, fallbackName: String): Coords {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?>
            ?: throw RuntimeException("Invalid geocoding response")
        val results = root["results"] as? List<Map<String, Any?>>
            ?: throw RuntimeException("No geocoding results for $fallbackName")
        val first = results.firstOrNull() ?: throw RuntimeException("No location found for $fallbackName")
        return Coords(
            latitude = (first["latitude"] as Number).toDouble(),
            longitude = (first["longitude"] as Number).toDouble(),
            name = first["name"] as? String ?: fallbackName
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCurrentWeather(json: String, locationName: String): WeatherInfo {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?>
            ?: throw RuntimeException("Invalid weather response")
        val current = root["current"] as? Map<String, Any?>
            ?: throw RuntimeException("No current weather data")

        val temp = (current["temperature_2m"] as Number).toDouble()
        val humidity = (current["relative_humidity_2m"] as Number).toInt()
        val wind = (current["wind_speed_10m"] as Number).toDouble()
        val code = (current["weather_code"] as Number).toInt()

        return WeatherInfo(
            location = locationName,
            temperatureC = temp,
            condition = weatherCodeToText(code),
            humidity = humidity,
            windKph = wind
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseForecast(json: String): List<DayForecast> {
        val root = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any?>
            ?: throw RuntimeException("Invalid forecast response")
        val daily = root["daily"] as? Map<String, Any?>
            ?: throw RuntimeException("No daily forecast")

        val times = daily["time"] as List<String>
        val mins = daily["temperature_2m_min"] as List<Number>
        val maxs = daily["temperature_2m_max"] as List<Number>
        val codes = daily["weather_code"] as List<Number>

        return times.indices.map { i ->
            DayForecast(
                date = times[i],
                minTempC = mins[i].toDouble(),
                maxTempC = maxs[i].toDouble(),
                condition = weatherCodeToText(codes[i].toInt())
            )
        }
    }

    private fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
