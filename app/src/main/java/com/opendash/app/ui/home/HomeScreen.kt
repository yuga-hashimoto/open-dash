package com.opendash.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import android.text.format.DateFormat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.ui.ambient.ActiveTimersCard
import com.opendash.app.ui.common.isExpandedLandscape
import com.opendash.app.ui.theme.SpeakerBackground
import com.opendash.app.ui.theme.SpeakerTextPrimary
import com.opendash.app.ui.theme.SpeakerTextSecondary
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    // Clock tick lives in the Composable so HomeViewModel stays testable
    // (a perpetual delay loop inside viewModelScope would hang runTest).
    var time by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalDateTime.now()
            delay(1000L)
        }
    }
    val weather by viewModel.weather.collectAsState()
    val onlineWeather by viewModel.onlineWeather.collectAsState()
    val headlines by viewModel.headlines.collectAsState()
    val chips by viewModel.deviceChips.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val activeTimers by viewModel.activeTimers.collectAsState()
    val thermal by viewModel.thermalLevel.collectAsState()
    val nextEvent by viewModel.nextEvent.collectAsState()

    val wide = isExpandedLandscape()

    val topPad = 32.dp

    // `systemBarsPadding()` guards the standalone usage path (previews, direct
    // navigation) — when HomeScreen is hosted inside ModeScaffold the outer Box
    // has already consumed the insets so this becomes a no-op.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SpeakerBackground)
            .systemBarsPadding()
    ) {
        if (wide) {
            // Tablet landscape — Alexa-Show style ambient layout.
            // Single Column flow (top → spacer → headlines → bottom spacer)
            // guarantees no two clusters overlap, unlike the previous
            // free-Box align approach where the clock and the centered
            // headlines strip stacked on top of each other.
            //   top-left  — compact clock + date + weather card
            //   middle    — auto-advancing headlines ticker
            //   bottom    — secondary status (events / timers / chips)
            //   overlays  — settings gear (top-right, ModeScaffold),
            //               mic FAB (bottom-right, ModeScaffold),
            //               suggestion bubble (bottom-center, this file)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp, end = 32.dp, top = topPad, bottom = 32.dp)
            ) {
                // Echo Show inline header: time · weather icon · temp · date+location
                // All on a single Row at ~28 sp so the ambient screen is
                // dominated by the centered news card, not the chrome.
                AmbientHeader(time = time, weatherState = onlineWeather)

                // Push the news strip and any secondary status all the
                // way to the bottom — the mic FAB is gone so there's no
                // Asymmetric spacers — 3 parts top, 1 part bottom — lift
                // the news strip off the very bottom edge. Leaves room
                // for the ListeningBar to slide in from BottomCenter
                // without hiding the last line of the news card.
                Spacer(modifier = Modifier.weight(3f))

                HeadlinesBlock(
                    state = headlines,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                )

                if (nextEvent != null || activeTimers.isNotEmpty() || chips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(modifier = Modifier.widthIn(max = 420.dp)) {
                        nextEvent?.let {
                            NextEventCard(event = it)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (activeTimers.isNotEmpty()) {
                            ActiveTimersCard(
                                timers = activeTimers,
                                onCancelTimer = viewModel::onCancelTimer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (chips.isNotEmpty()) {
                            DeviceStatusChips(chips = chips)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            // Portrait / compact: same widget set, single stacked column.
            // Clock + weather still dominate the top half; the ticker
            // remains at the bottom so the eye rests on time and
            // temperature first.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp, end = 32.dp, top = topPad, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(0.2f))
                ClockWidget(time = time, largeMode = true)
                Spacer(modifier = Modifier.height(28.dp))
                WeatherBlock(state = onlineWeather, sensorWeather = weather)
                nextEvent?.let {
                    Spacer(modifier = Modifier.height(20.dp))
                    NextEventCard(event = it)
                }
                if (activeTimers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    ActiveTimersCard(
                        timers = activeTimers,
                        onCancelTimer = viewModel::onCancelTimer
                    )
                }
                Spacer(modifier = Modifier.height(36.dp))
                if (chips.isNotEmpty()) {
                    DeviceStatusChips(chips = chips)
                }
                HeadlinesBlock(
                    state = headlines,
                    modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        val playing = nowPlaying
        if (playing != null) {
            NowPlayingBar(
                nowPlaying = playing,
                onMediaAction = { action ->
                    viewModel.dispatchMediaAction(playing.deviceId, action)
                },
                onVolumeChange = { level ->
                    viewModel.dispatchMediaVolume(playing.deviceId, level)
                },
                onShuffleToggle = { enabled ->
                    viewModel.dispatchShuffle(playing.deviceId, enabled)
                },
                onRepeatChange = { mode ->
                    viewModel.dispatchRepeat(playing.deviceId, mode)
                },
                onSourceSelected = { source ->
                    viewModel.dispatchSelectSource(playing.deviceId, source)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
            )
        }

        // Top-right (offset to avoid the settings gear): thermal throttle
        // badge only. Renders nothing in the common-case NORMAL state.
        // Battery chip was removed because it overlapped with the gear.
        TabletStatusChips(
            thermal = thermal,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 64.dp)
        )

        // Proactive SuggestionBubble intentionally not rendered on the
        // ambient Home — the user found the recurring popup distracting.
        // Suggestions still flow into voice / proactive paths via
        // SuggestionEngine; only the on-screen card is suppressed here.
    }
}

/**
 * Routes an online-weather [BriefingState] to the right variant of the
 * weather tile. On success with data we show the full Alexa-style card;
 * on success with null we fall back to the sensor-based [WeatherWidget]
 * (HA / SwitchBot-provided temps) so the tile doesn't disappear when
 * online data is merely unavailable but local data is present. On
 * Loading / Error we show the matching skeleton / explainer card.
 */
@Composable
private fun WeatherBlock(
    state: BriefingState<com.opendash.app.tool.info.WeatherInfo?>,
    sensorWeather: WeatherData?,
    modifier: Modifier = Modifier,
) {
    when (state) {
        BriefingState.Loading -> OnlineWeatherCardLoading(modifier = modifier)
        is BriefingState.Success -> {
            val info = state.data
            if (info != null) {
                OnlineWeatherCard(weather = info, modifier = modifier)
            } else {
                WeatherWidget(weather = sensorWeather, modifier = modifier)
            }
        }
        is BriefingState.Error -> OnlineWeatherCardError(
            kind = state.kind,
            modifier = modifier,
        )
    }
}

/**
 * Echo Show inline ambient header. Renders one Row at ~28 sp:
 *   `HH:mm  ☁  18°    日 4/19 · Munakata`
 *
 * Time and temperature share a single typography scale so the header
 * reads as one continuous status line rather than three competing
 * widgets. Date+location collapse to a soft secondary color so the
 * eye lands on time/temp first. Loading / error states omit the
 * weather portion silently — an ambient screen shouldn't be displaying
 * "loading…" copy in chrome.
 */
@Composable
private fun AmbientHeader(
    time: LocalDateTime,
    weatherState: BriefingState<com.opendash.app.tool.info.WeatherInfo?>,
    modifier: Modifier = Modifier,
) {
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val timePattern = if (use24Hour) "HH:mm" else "h:mm"
    val info = (weatherState as? BriefingState.Success)?.data
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time.format(DateTimeFormatter.ofPattern(timePattern)),
            style = MaterialTheme.typography.headlineMedium,
            color = SpeakerTextPrimary,
            fontWeight = FontWeight.Normal,
        )
        if (info != null) {
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                imageVector = conditionIcon(info.condition),
                contentDescription = info.condition,
                tint = SpeakerTextPrimary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${info.temperatureC.toInt()}°",
                style = MaterialTheme.typography.headlineMedium,
                color = SpeakerTextPrimary,
                fontWeight = FontWeight.Light,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = buildString {
                append(time.format(DateTimeFormatter.ofPattern("E M/d")))
                if (info != null) append(" · ${info.location}")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = SpeakerTextSecondary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(64.dp)) // settings gear slot
    }
}

/**
 * Routes a headlines [BriefingState] to the right variant of the tile
 * strip. Success-with-empty collapses to nothing (legitimate "no news
 * today"); Loading and Error are explicit so users can tell the fetch
 * itself failed.
 */
@Composable
private fun HeadlinesBlock(
    state: BriefingState<List<com.opendash.app.tool.info.NewsItem>>,
    modifier: Modifier = Modifier,
) {
    when (state) {
        BriefingState.Loading -> HeadlinesCardLoading(modifier = modifier)
        is BriefingState.Success -> {
            if (state.data.isNotEmpty()) {
                HeadlinesCard(headlines = state.data.take(1), modifier = modifier)
            }
        }
        is BriefingState.Error -> HeadlinesCardError(
            kind = state.kind,
            modifier = modifier,
        )
    }
}
