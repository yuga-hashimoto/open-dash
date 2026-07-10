package com.opendash.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.R
import com.opendash.app.data.preferences.PreferenceKeys

/**
 * First-run choice between the embedded on-device model and a cloud API
 * provider. Shown once, before ModelSetupScreen/OnboardingScreen — see
 * MainActivity's mode gate. Selecting either mode persists it immediately;
 * MainActivity observes the same preference and advances automatically.
 */
@Composable
fun ProviderModeScreen(
    onModeSelected: () -> Unit,
    viewModel: ProviderModeViewModel = hiltViewModel()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_mode_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.provider_mode_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ModeOptionCard(
            title = stringResource(R.string.provider_mode_local_title),
            description = stringResource(R.string.provider_mode_local_description),
            onClick = {
                viewModel.selectMode(PreferenceKeys.MODE_LOCAL)
                onModeSelected()
            }
        )
        ModeOptionCard(
            title = stringResource(R.string.provider_mode_api_title),
            description = stringResource(R.string.provider_mode_api_description),
            onClick = {
                viewModel.selectMode(PreferenceKeys.MODE_API)
                onModeSelected()
            }
        )
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
