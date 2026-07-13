package com.opendash.app.ui.settings.vad

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.R

/**
 * P16.2 final-mile: lets the user trigger the Silero VAD model download
 * from Settings instead of it only being reachable by manually placing the
 * file on-device. Shown alongside [com.opendash.app.ui.settings.whisper.WhisperModelsCard]
 * since Silero VAD only feeds the Whisper capture path today.
 */
@Composable
fun SileroVadCard(
    modifier: Modifier = Modifier,
    viewModel: SileroVadSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_silero_vad_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_silero_vad_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            when (state.status) {
                SileroVadSettingsViewModel.Status.NOT_DOWNLOADED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_silero_vad_size, state.totalMb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = viewModel::startDownload) {
                            Text(stringResource(R.string.settings_silero_vad_download))
                        }
                    }
                }
                SileroVadSettingsViewModel.Status.DOWNLOADING -> {
                    Text(
                        text = stringResource(R.string.settings_silero_vad_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_silero_vad_progress,
                            state.downloadedMb,
                            state.totalMb
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SileroVadSettingsViewModel.Status.READY -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_silero_vad_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = viewModel::delete) {
                            Text(stringResource(R.string.settings_silero_vad_delete))
                        }
                    }
                }
                SileroVadSettingsViewModel.Status.ERROR -> {
                    Text(
                        text = stringResource(
                            R.string.settings_silero_vad_error,
                            state.errorMessage.orEmpty()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = viewModel::startDownload) {
                        Text(stringResource(R.string.settings_silero_vad_download))
                    }
                }
            }
        }
    }
}
