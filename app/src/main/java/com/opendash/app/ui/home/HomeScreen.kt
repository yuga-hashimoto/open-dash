package com.opendash.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp
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
    val onlineWeather by viewModel.onlineWeather.collectAsState()
    val headlines by viewModel.headlines.collectAsState()
    val chips by viewModel.deviceChips.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val activeTimers by viewModel.activeTimers.collectAsState()
    val thermal by viewModel.thermalLevel.collectAsState()
    val saverState by viewModel.saverState.collectAsState()
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
                // P14.8 saver banner — only rendered when active, so the
                // layout stays identical in the happy path.
                HomeSaverBanner(saverState = saverState)

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
            // Portrait / compact: Echo Show-style ambient layout adapted
            // for the vertical aspect. The clock is the hero; an inline
            // date · weather row sits directly beneath it at a softer
            // weight (mirrors landscape's AmbientHeader but stacked).
            // Secondary status clusters compactly above the centered
            // news strip so the reading rhythm top-to-bottom is:
            //   hero clock → date+temp → whitespace → status → news.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp, end = 32.dp, top = topPad, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // P14.8 saver banner — portrait variant appears above the
                // hero clock, else the layout stays identical.
                HomeSaverBanner(saverState = saverState)

                // Top breathing space — pushes the hero down off the
                // status bar so the eye lands on the clock, not chrome.
                Spacer(modifier = Modifier.weight(1f))

                // Hero clock — inline instead of ClockWidget because the
                // portrait width (~360dp on phones) can't fit ClockWidget's
                // 180sp typography without wrapping, and ClockWidget's
                // built-in "EEEE, MMMM d" subtitle duplicates the date that
                // PortraitAmbientSubheader already shows. Single source of
                // truth: time here, date+temp in the subheader below.
                PortraitHeroClock(time = time)
                Spacer(modifier = Modifier.height(12.dp))
                PortraitAmbientSubheader(time = time, weatherState = onlineWeather)

                // Generous gap between the hero and the secondary info
                // cluster — this is the asymmetry that made the
                // landscape layout feel like an Echo Show, not a
                // dashboard. 3:2 ratio keeps the bottom content from
                // feeling orphaned.
                Spacer(modifier = Modifier.weight(1.2f))

                // Secondary status cluster — events, timers, chips.
                // Width-capped so on wider portrait tablets it doesn't
                // stretch edge-to-edge and lose its card feel.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                ) {
                    nextEvent?.let {
                        NextEventCard(event = it, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (activeTimers.isNotEmpty()) {
                        ActiveTimersCard(
                            timers = activeTimers,
                            onCancelTimer = viewModel::onCancelTimer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (chips.isNotEmpty()) {
                        DeviceStatusChips(chips = chips)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Centered headlines strip — capped like the landscape
                // version so tile widths stay consistent across layouts.
                HeadlinesBlock(
                    state = headlines,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                )

                // Bottom spacer leaves room for the NowPlayingBar /
                // ListeningBar to slide in without masking content.
                Spacer(modifier = Modifier.height(16.dp))
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
 * Portrait hero clock. Uses a reduced 120sp vs. ClockWidget's 180sp
 * because on a ~360dp-wide phone the larger size wraps "HH:MM" onto a
 * second line (observed regression). `softWrap = false` belt-and-braces
 * against any future font that measures wider than expected.
 *
 * No date subtitle — [PortraitAmbientSubheader] owns date rendering so
 * the two widgets don't duplicate.
 */
@Composable
private fun PortraitHeroClock(
    time: LocalDateTime,
    modifier: Modifier = Modifier,
) {
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    Text(
        text = formatHourMinute(time, use24Hour),
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
        color = SpeakerTextPrimary,
        fontWeight = FontWeight.Thin,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/**
 * Portrait variant of [AmbientHeader] — a single, centered inline row
 * that sits directly under the hero clock. Reads as a subtitle to the
 * time: `☁  18°  ·  日 4/19  ·  Munakata`. Uses a softer typography scale
 * than the landscape header because the clock above is already bearing
 * the "dominant" role, so this line is deliberately de-emphasised.
 *
 * When the online weather fetch hasn't landed yet we still render the
 * date — an ambient screen shouldn't go blank just because the network
 * is slow.
 */
@Composable
private fun PortraitAmbientSubheader(
    time: LocalDateTime,
    weatherState: BriefingState<com.opendash.app.tool.info.WeatherInfo?>,
    modifier: Modifier = Modifier,
) {
    val info = (weatherState as? BriefingState.Success)?.data
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (info != null) {
            Icon(
                imageVector = conditionIcon(info.condition),
                contentDescription = info.condition,
                tint = SpeakerTextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${info.temperatureC.toInt()}°",
                style = MaterialTheme.typography.titleLarge,
                color = SpeakerTextSecondary,
                fontWeight = FontWeight.Light,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "·",
                style = MaterialTheme.typography.titleMedium,
                color = SpeakerTextSecondary,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = buildString {
                append(time.format(DateTimeFormatter.ofPattern("E M/d")))
                if (info != null) append("  ·  ${info.location}")
            },
            style = MaterialTheme.typography.titleMedium,
            color = SpeakerTextSecondary,
            fontWeight = FontWeight.Light,
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
