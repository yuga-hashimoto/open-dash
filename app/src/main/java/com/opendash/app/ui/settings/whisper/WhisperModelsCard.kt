package com.opendash.app.ui.settings.whisper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * P14.1 Whisper models card. Shipped in the Settings STT section so
 * users can download offline models when they select the
 * "Whisper (offline)" STT provider. The card renders a row per
 * catalogue entry with display name, size, download / delete button,
 * and an in-flight progress bar for the model the user is downloading
 * right now.
 */
@Composable
fun WhisperModelsCard(
    modifier: Modifier = Modifier,
    viewModel: WhisperSettingsViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()
    val language by viewModel.language.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_whisper_models_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_whisper_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            rows.forEach { row ->
                Spacer(Modifier.height(12.dp))
                WhisperModelRow(
                    row = row,
                    onDownload = { viewModel.startDownload(row.model) },
                    onDelete = { viewModel.delete(row.model) },
                    onSelectActive = { viewModel.setActive(row.model) }
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_whisper_language_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_whisper_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WhisperSettingsViewModel.SUPPORTED_LANGUAGES.forEach { (code, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = language == code,
                        onClick = { viewModel.setLanguage(code) }
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun WhisperModelRow(
    row: WhisperSettingsViewModel.Row,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelectActive: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active-model radio lets the user pick which downloaded model
        // WhisperSttProvider should use; clicking it only persists the
        // preference — no download is triggered.
        RadioButton(
            selected = row.isActive,
            onClick = onSelectActive,
            enabled = row.installed
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (row.isDownloading) {
                    stringResource(
                        R.string.settings_whisper_model_progress,
                        row.downloadedMb,
                        row.totalMb
                    )
                } else {
                    stringResource(R.string.settings_whisper_model_size, row.model.sizeMb)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            row.installed -> OutlinedButton(onClick = onDelete) {
                Text(stringResource(R.string.settings_whisper_model_delete))
            }
            row.isDownloading -> Text(
                text = stringResource(R.string.settings_whisper_model_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            else -> OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.settings_whisper_model_download))
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
