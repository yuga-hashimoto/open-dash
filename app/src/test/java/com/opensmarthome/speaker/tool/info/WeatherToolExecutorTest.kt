package com.opensmarthome.speaker.tool.info

import com.google.common.truth.Truth.assertThat
import com.opensmarthome.speaker.tool.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WeatherToolExecutorTest {

    private lateinit var executor: WeatherToolExecutor
    private lateinit var weatherProvider: WeatherProvider

    @BeforeEach
    fun setup() {
        weatherProvider = mockk(relaxed = true)
        executor = WeatherToolExecutor(weatherProvider)
    }

    @Test
    fun `availableTools returns weather tools`() = runTest {
        val tools = executor.availableTools()
        val names = tools.map { it.name }

        assertThat(names).contains("get_weather")
        assertThat(names).contains("get_forecast")
    }

    @Test
    fun `get_weather returns current conditions`() = runTest {
        coEvery { weatherProvider.getCurrent("Tokyo") } returns WeatherInfo(
            location = "Tokyo",
            temperatureC = 22.5,
            condition = "Partly cloudy",
            humidity = 60,
            windKph = 10.0
        )

        val result = executor.execute(
            ToolCall(id = "1", name = "get_weather", arguments = mapOf(
                "location" to "Tokyo"
            ))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Tokyo")
        assertThat(result.data).contains("22.5")
        assertThat(result.data).contains("Partly cloudy")
    }

    @Test
    fun `get_weather without location uses default`() = runTest {
        coEvery { weatherProvider.getCurrent(null) } returns WeatherInfo(
            location = "Current location",
            temperatureC = 15.0,
            condition = "Clear",
            humidity = 50,
            windKph = 5.0
        )

        val result = executor.execute(
            ToolCall(id = "2", name = "get_weather", arguments = emptyMap())
        )

        assertThat(result.success).isTrue()
    }

    @Test
    fun `get_forecast returns multiple days`() = runTest {
        coEvery { weatherProvider.getForecast("Tokyo", 3) } returns listOf(
            DayForecast("2026-04-16", 15.0, 25.0, "Sunny"),
            DayForecast("2026-04-17", 12.0, 20.0, "Cloudy"),
            DayForecast("2026-04-18", 10.0, 18.0, "Rain")
        )

        val result = executor.execute(
            ToolCall(id = "3", name = "get_forecast", arguments = mapOf(
                "location" to "Tokyo",
                "days" to 3.0
            ))
        )

        assertThat(result.success).isTrue()
        assertThat(result.data).contains("Sunny")
        assertThat(result.data).contains("Cloudy")
        assertThat(result.data).contains("Rain")
    }

    @Test
    fun `weather error returns failure`() = runTest {
        coEvery { weatherProvider.getCurrent(any()) } throws RuntimeException("Network error")

        val result = executor.execute(
            ToolCall(id = "4", name = "get_weather", arguments = mapOf(
                "location" to "Tokyo"
            ))
        )

        assertThat(result.success).isFalse()
    }
}
