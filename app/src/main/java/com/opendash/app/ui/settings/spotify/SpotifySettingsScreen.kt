package com.opendash.app.ui.settings.spotify

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendash.app.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettingsScreen(
    onBack: () -> Unit,
    viewModel: SpotifySettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spotify_settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (state.connected) stringResource(R.string.spotify_status_connected)
                else stringResource(R.string.spotify_status_not_connected),
                style = MaterialTheme.typography.titleMedium,
                color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.clientId,
                onValueChange = viewModel::setClientId,
                label = { Text(stringResource(R.string.spotify_client_id_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.spotify_client_id_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.spotify_redirect_uri_label),
                style = MaterialTheme.typography.labelMedium
            )
            Text(text = state.redirectUri, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.spotify_redirect_uri_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.connected) {
                OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.spotify_disconnect_button))
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            val url = viewModel.buildAuthorizationUrl() ?: return@launch
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    enabled = state.clientId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.spotify_connect_button))
                }
            }
        }
    }
}
