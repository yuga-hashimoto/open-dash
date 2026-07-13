package com.opendash.app.ui.settings.wakeword

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.R
import com.opendash.app.service.VoiceService

/**
 * P21.7 final-mile: opt-in wake-word engine picker. Vosk stays selected by
 * default for every existing user — openWakeWord only recognizes the fixed
 * English "hey jarvis" phrase, so switching to it is a deliberate,
 * explicit choice this card exists to make.
 */
@Composable
fun WakeWordEngineCard(
    modifier: Modifier = Modifier,
    viewModel: WakeWordEngineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_wake_word_engine_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_wake_word_engine_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.selectEngine(VoiceService.ENGINE_VOSK) },
                    colors = if (state.selectedEngine == VoiceService.ENGINE_VOSK) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(stringResource(R.string.settings_wake_word_engine_vosk)) }

                Button(
                    onClick = {
                        if (state.downloadStatus == WakeWordEngineViewModel.DownloadStatus.READY) {
                            viewModel.selectEngine(VoiceService.ENGINE_OPEN_WAKE_WORD)
                        } else {
                            viewModel.startDownload()
                        }
                    },
                    enabled = state.downloadStatus != WakeWordEngineViewModel.DownloadStatus.DOWNLOADING,
                    colors = if (state.selectedEngine == VoiceService.ENGINE_OPEN_WAKE_WORD) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(
                        when (state.downloadStatus) {
                            WakeWordEngineViewModel.DownloadStatus.READY ->
                                stringResource(R.string.settings_wake_word_engine_open_wake_word)
                            WakeWordEngineViewModel.DownloadStatus.DOWNLOADING ->
                                stringResource(R.string.settings_wake_word_engine_downloading)
                            else ->
                                stringResource(R.string.settings_wake_word_engine_download)
                        }
                    )
                }
            }

            if (state.selectedEngine == VoiceService.ENGINE_OPEN_WAKE_WORD) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_wake_word_engine_open_wake_word_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.downloadStatus == WakeWordEngineViewModel.DownloadStatus.ERROR) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.settings_wake_word_engine_error,
                        state.errorMessage.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
