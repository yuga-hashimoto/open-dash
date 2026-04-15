package com.opensmarthome.speaker.ui.setup

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opensmarthome.speaker.assistant.provider.embedded.ModelDownloadState
import com.opensmarthome.speaker.ui.theme.SpeakerBackground
import com.opensmarthome.speaker.ui.theme.SpeakerPrimary
import com.opensmarthome.speaker.ui.theme.SpeakerTextPrimary
import com.opensmarthome.speaker.ui.theme.SpeakerTextSecondary
import com.opensmarthome.speaker.ui.theme.VoiceError
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ModelSetupScreen(
    downloadState: StateFlow<ModelDownloadState>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by downloadState.collectAsState()

    val transition = rememberInfiniteTransition(label = "pulse")
    val iconAlpha by transition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "iconAlpha"
    )

    Box(
        modifier = modifier.fillMaxSize().background(SpeakerBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp)
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = SpeakerPrimary,
                modifier = Modifier.size(80.dp).alpha(
                    if (state is ModelDownloadState.Downloading) iconAlpha else 1f
                )
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = when (state) {
                    is ModelDownloadState.NotStarted -> "Setting up AI..."
                    is ModelDownloadState.Checking -> "Checking model..."
                    is ModelDownloadState.Downloading -> "Downloading AI Model"
                    is ModelDownloadState.Ready -> "Ready!"
                    is ModelDownloadState.Error -> "Setup Failed"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = SpeakerTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is ModelDownloadState.Downloading -> {
                    Text(
                        text = "Gemma 3 1B — ${s.downloadedMb}MB / ${s.totalMb}MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpeakerTextSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(6.dp),
                        color = SpeakerPrimary,
                        trackColor = SpeakerBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${(s.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = SpeakerPrimary
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "This only happens once. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpeakerTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                is ModelDownloadState.Checking, is ModelDownloadState.NotStarted -> {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(0.4f).height(4.dp),
                        color = SpeakerPrimary,
                        trackColor = SpeakerBackground
                    )
                }
                is ModelDownloadState.Error -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoiceError,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = SpeakerPrimary)
                    ) {
                        Text("Try Again")
                    }
                }
                is ModelDownloadState.Ready -> {
                    Text(
                        text = "AI model ready. Starting...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpeakerTextSecondary
                    )
                }
            }
        }
    }
}
