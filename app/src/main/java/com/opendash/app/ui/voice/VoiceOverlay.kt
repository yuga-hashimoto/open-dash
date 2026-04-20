package com.opendash.app.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opendash.app.ui.theme.SpeakerBackground
import com.opendash.app.ui.theme.SpeakerTextPrimary
import com.opendash.app.ui.theme.SpeakerTextSecondary
import com.opendash.app.ui.theme.SpeakerTextTertiary
import com.opendash.app.ui.theme.VoiceError
import com.opendash.app.voice.pipeline.VoicePipelineState

/**
 * Full-screen overlay shown during a voice interaction. During
 * [VoicePipelineState.Speaking] it renders [spokenText] (the sentence
 * currently being spoken by the TTS engine) in a large Alexa/Nest-Hub-style
 * headline that swaps with an [AnimatedContent] crossfade as each chunk
 * finishes — "karaoke" rolling. When the TTS provider does not stream
 * chunks, [spokenText] is empty and the full [responseText] is shown
 * instead so behaviour degrades gracefully.
 */
@Composable
fun VoiceOverlay(
    voiceState: VoicePipelineState,
    sttText: String,
    responseText: String,
    spokenText: String = "",
    modifier: Modifier = Modifier
) {
    val visible = voiceState !is VoicePipelineState.Idle &&
            voiceState !is VoicePipelineState.WakeWordListening

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
        exit = fadeOut(tween(500))
    ) {
        // Overlay is laid out inside ModeScaffold's inset-consuming root Box,
        // so this `systemBarsPadding()` is a safety net: if the overlay is
        // ever hoisted to MainActivity.setContent directly (tests, previews,
        // bug reproductions) the headline text still clears the status bar.
        // Two-row response screen:
        //   top    — what the user said (quoted, secondary colour)
        //   middle — divider
        //   bottom — AI reply rolled karaoke-style (displaySmall)
        // Keeps the conversation legible on a tablet at a glance.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(SpeakerBackground.copy(alpha = 0.94f))
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Top: user's utterance.
            if (sttText.isNotBlank()) {
                Text(
                    text = "\u300C$sttText\u300D",
                    style = MaterialTheme.typography.titleLarge,
                    color = SpeakerTextSecondary,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Thin divider between user and assistant.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SpeakerTextTertiary.copy(alpha = 0.35f))
            )

            Spacer(Modifier.height(24.dp))

            // State indicator (only shown while assistant is still
            // processing — goes away once karaoke starts).
            val stateLabel = when (voiceState) {
                is VoicePipelineState.Processing -> "処理中…"
                is VoicePipelineState.Thinking -> "考えています…"
                is VoicePipelineState.PreparingSpeech -> "準備中…"
                else -> ""
            }
            if (stateLabel.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceStateAnimation(state = voiceState)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = SpeakerTextTertiary,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // Bottom: AI reply — karaoke-style crossfade between chunks
            // while Speaking, fallback to the full response otherwise.
            //
            // During Speaking we deliberately never fall back to responseText,
            // even when spokenText is briefly blank between sentence
            // boundaries: the Speaking state is the karaoke window, and
            // showing the entire reply for a frame caused a "full text
            // flash" at the start and end of TTS playback. TtsManager
            // seeds spokenText with the full text for single-shot
            // providers (OpenAI / ElevenLabs / VOICEVOX), so this branch
            // still renders a non-blank string on every supported route.
            val displayText = when {
                voiceState is VoicePipelineState.Speaking -> spokenText
                else -> responseText
            }

            if (displayText.isNotBlank()) {
                AnimatedContent(
                    targetState = displayText,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "karaoke-chunk"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (voiceState is VoicePipelineState.Error) VoiceError
                        else SpeakerTextPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .animateContentSize(tween(240))
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}
