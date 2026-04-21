package com.opendash.app.ui.settings.termux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
 * P19.2 Termux Bridge opt-in card. Designed to live at the very end of
 * [com.opendash.app.ui.settings.SettingsScreen] under an "Advanced"
 * section header so it's not the first thing a casual user sees — the
 * feature lets the assistant run arbitrary shell commands, so surfacing
 * it near the dangerous-settings tail reinforces the power-user framing.
 */
@Composable
fun TermuxBridgeSettingsCard(
    modifier: Modifier = Modifier,
    viewModel: TermuxSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val gatesSatisfied =
        state.enabled && state.termuxInstalled && state.permissionGranted

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_termux_bridge_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_termux_bridge_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_termux_bridge_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            if (state.enabled) {
                Spacer(Modifier.height(8.dp))
                StatusLine(
                    ok = state.termuxInstalled,
                    okText = stringResource(R.string.settings_termux_bridge_status_installed),
                    missingText = stringResource(R.string.settings_termux_bridge_status_not_installed)
                )
                StatusLine(
                    ok = state.permissionGranted,
                    okText = stringResource(R.string.settings_termux_bridge_status_permission_granted),
                    missingText = stringResource(R.string.settings_termux_bridge_status_permission_missing)
                )

                if (!gatesSatisfied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_termux_bridge_gate_reminder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    ok: Boolean,
    okText: String,
    missingText: String
) {
    val prefix = if (ok) "\u2713 " else "\u2717 "
    Text(
        text = prefix + if (ok) okText else missingText,
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}
