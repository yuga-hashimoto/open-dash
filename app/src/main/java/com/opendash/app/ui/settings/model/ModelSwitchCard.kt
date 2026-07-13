package com.opendash.app.ui.settings.model

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
import androidx.compose.material3.OutlinedButton
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
 * P16.6 final-mile: switch the embedded LLM's active model without
 * restarting the app. Only shows a picker when more than one model is
 * present on disk — with the default onboarding download flow that
 * deletes prior models on every new download (kept as-is, a deliberate
 * user decision — see [ModelSwitchViewModel]'s KDoc), that means a second
 * model via manual import ([com.opendash.app.assistant.provider.embedded.ModelManager.importModel])
 * is what makes this card do anything.
 */
@Composable
fun ModelSwitchCard(
    modifier: Modifier = Modifier,
    viewModel: ModelSwitchViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()
    val switchState by viewModel.switchState.collectAsState()

    if (rows.size < 2) return

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_model_switch_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_model_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (switchState == ModelSwitchViewModel.SwitchState.FAILED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_model_switch_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.model.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = row.model.sizeMb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (row.isActive) {
                        Text(
                            text = stringResource(R.string.settings_model_switch_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.switchTo(row.model) },
                            enabled = switchState != ModelSwitchViewModel.SwitchState.SWITCHING
                        ) {
                            Text(
                                if (switchState == ModelSwitchViewModel.SwitchState.SWITCHING) {
                                    stringResource(R.string.settings_model_switch_switching)
                                } else {
                                    stringResource(R.string.settings_model_switch_action)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
