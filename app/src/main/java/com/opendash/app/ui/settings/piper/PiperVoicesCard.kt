package com.opendash.app.ui.settings.piper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.R

/**
 * P14.9 Piper voices Settings card — parallels `WhisperModelsCard`.
 * Renders when the TTS provider is "piper" so the user can pick and
 * download voices. Each row: display name + language tag + size /
 * download progress + action button.
 */
@Composable
fun PiperVoicesCard(
    modifier: Modifier = Modifier,
    viewModel: PiperSettingsViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_piper_voices_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_piper_voices_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            rows.forEach { row ->
                Spacer(Modifier.height(12.dp))
                PiperVoiceRow(
                    row = row,
                    onDownload = { viewModel.startDownload(row.voice) },
                    onDelete = { viewModel.delete(row.voice) },
                    onSelectActive = { viewModel.setActive(row.voice) },
                    onPreview = { viewModel.preview(row.voice) }
                )
            }
        }
    }
}

@Composable
private fun PiperVoiceRow(
    row: PiperSettingsViewModel.Row,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelectActive: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = row.isActive,
            onClick = onSelectActive,
            enabled = row.installed
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.voice.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when {
                    row.isDownloading && row.activeFile != null -> stringResource(
                        R.string.settings_piper_voice_progress,
                        row.downloadedMb,
                        row.totalMb,
                        row.activeFile
                    )
                    else -> stringResource(
                        R.string.settings_piper_voice_size,
                        row.voice.languageTag,
                        row.voice.modelSizeMb
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            row.installed -> {
                OutlinedButton(onClick = onPreview) {
                    Text(stringResource(R.string.settings_piper_voice_preview))
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = onDelete) {
                    Text(stringResource(R.string.settings_piper_voice_delete))
                }
            }
            row.isDownloading -> Text(
                text = stringResource(R.string.settings_piper_voice_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            else -> OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.settings_piper_voice_download))
            }
        }
    }
    if (row.isDownloading && row.progress > 0f) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { row.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
