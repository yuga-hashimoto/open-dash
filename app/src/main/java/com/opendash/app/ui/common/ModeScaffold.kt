package com.opendash.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import com.opendash.app.ui.theme.VoiceListening
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.ui.conversations.ConversationsScreen
import com.opendash.app.ui.devices.DevicesScreen
import com.opendash.app.ui.home.ConnectionBadge
import com.opendash.app.ui.home.ConnectionStatus
import com.opendash.app.ui.home.HomeScreen
import com.opendash.app.ui.home.NightClockOverlay
import com.opendash.app.ui.settings.SettingsScreen
import com.opendash.app.ui.theme.SpeakerBackground
import com.opendash.app.ui.theme.SpeakerOnPrimary
import com.opendash.app.ui.theme.SpeakerPrimary
import com.opendash.app.ui.theme.SpeakerTextSecondary
import com.opendash.app.ui.theme.SpeakerTextTertiary
import com.opendash.app.ui.theme.VoiceListening
import com.opendash.app.ui.voice.VoiceOverlay
import com.opendash.app.voice.pipeline.VoicePipelineState
import kotlinx.coroutines.delay

@Composable
fun ModeScaffold(
    viewModel: ModeScaffoldViewModel = hiltViewModel()
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val sttText by viewModel.partialText.collectAsState()
    val responseText by viewModel.lastResponse.collectAsState()
    val spokenText by viewModel.currentSpokenText.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    var showSettings by remember { mutableStateOf(false) }
    var showNightClock by remember { mutableStateOf(false) }
    var showControlDrawer by remember { mutableStateOf(false) }
    // Keep the ambient Home visible while the mic is still listening:
    // only switch to the full VoiceOverlay once the assistant is
    // actually thinking / speaking. Listening gets an in-place bottom
    // bar drawn below (ListeningBar) that matches the Alexa / Nest Hub
    // pattern — the clock + news stay on screen while the user talks.
    val showOverlay = voiceState is VoicePipelineState.Processing ||
            voiceState is VoicePipelineState.Thinking ||
            voiceState is VoicePipelineState.PreparingSpeech ||
            voiceState is VoicePipelineState.Speaking ||
            voiceState is VoicePipelineState.Error
    val showListeningBar = voiceState is VoicePipelineState.Listening

    // Auto-return to Home after 30s inactivity on non-Home pages
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) {
            delay(30_000L)
            pagerState.animateScrollToPage(0)
        }
    }

    // `systemBarsPadding()` consumes the WindowInsets for every descendant so
    // the whole app shell (pager pages, connection badge, settings gear, voice
    // overlay, mic FAB) is kept clear of the status bar and gesture nav bar on
    // Android 15+ where `enableEdgeToEdge()` is mandatory. Applying it once on
    // the outer Box means child composables don't have to opt in individually;
    // Compose's inset consumption prevents double padding if a descendant
    // re-applies the modifier.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpeakerBackground)
            .systemBarsPadding()
    ) {
        // Main pages: Home → Conversations → Devices (swipe)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> HomeScreen()
                1 -> ConversationsScreen()
                2 -> DevicesScreen()
            }
        }

        // Page indicator dots intentionally hidden — they overlapped the
        // news card on the ambient Home. Horizontal swipe between Home,
        // Conversations, and Devices still works without the visual hint.

        // Connection badge intentionally hidden — the user found the
        // "0 devices" chip distracting on the ambient Home where it
        // overlapped with the clock cluster. Connection status surfaces
        // via the Settings → Devices screen and via voice when relevant.

        // Settings gear icon (top-right)
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .alpha(0.5f)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = SpeakerTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Control drawer (swipe down from top)
        ControlDrawer(
            visible = showControlDrawer,
            onNightMode = {
                showControlDrawer = false
                showNightClock = true
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Mic FAB intentionally hidden — voice input is wake-word driven
        // ("dash") so a tappable mic button isn't required on the
        // ambient Home. Removing it keeps the dashboard chrome-free and
        // closer to an Echo Show / Nest Hub aesthetic.

        // Bottom listening bar — appears in-place over the Home bottom
        // edge while the mic is active. Alexa / Nest Hub style: no
        // screen transition, just a colored indicator + partial STT.
        if (showListeningBar) {
            ListeningBar(
                partialText = sttText,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Voice Overlay — full-screen response view with user message
        // on top and the karaoke-style AI reply below.
        if (showOverlay) {
            VoiceOverlay(
                voiceState = voiceState,
                sttText = sttText,
                responseText = responseText,
                spokenText = spokenText
            )
        }

        // Settings overlay
        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it }
        ) {
            SettingsScreen(onBack = { showSettings = false })
        }

        // Night clock overlay
        if (showNightClock) {
            NightClockOverlay(onDismiss = { showNightClock = false })
        }
    }
}

/**
 * In-place listening indicator pinned to the bottom edge of the Home.
 * Renders a thin blue bar (pulsing) + the live STT partial text so the
 * user can see what the assistant heard without leaving the ambient
 * view. Appears only during [VoicePipelineState.Listening]; replaced by
 * the full [VoiceOverlay] once processing / speaking starts.
 */
@Composable
private fun ListeningBar(
    partialText: String,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "listening-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listening-alpha",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VoiceListening.copy(alpha = 0.14f * alpha))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (partialText.isNotBlank()) {
            Text(
                text = partialText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = VoiceListening.copy(alpha = alpha),
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "聞いています…",
                color = VoiceListening.copy(alpha = alpha),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MicFab(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fabSize by animateDpAsState(if (isListening) 72.dp else 56.dp, label = "fabSize")
    val glowAlpha by animateFloatAsState(if (isListening) 0.3f else 0f, label = "glow")
    // Dim the FAB while idle so the clock / weather / ticker own the
    // dashboard visually. Full opacity returns the instant the user taps
    // to listen so the feedback loop stays Alexa-crisp.
    val fabAlpha by animateFloatAsState(if (isListening) 1f else 0.75f, label = "fabAlpha")
    val transition = rememberInfiniteTransition(label = "breathe")
    val breatheScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        if (glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(fabSize + 20.dp)
                    .background(VoiceListening.copy(alpha = glowAlpha), CircleShape)
            )
        }
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(fabSize).scale(breatheScale).alpha(fabAlpha),
            containerColor = SpeakerPrimary,
            shape = CircleShape
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Voice",
                tint = SpeakerOnPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
