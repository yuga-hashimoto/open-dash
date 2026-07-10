package com.opendash.app.ui.settings.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendash.app.R
import com.opendash.app.assistant.provider.api.ApiProviderCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddApiProviderDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ApiProviderSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var presetMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_provider_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = presetMenuExpanded,
                    onExpandedChange = { presetMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state.selectedPreset?.displayName ?: stringResource(R.string.add_provider_select_preset),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.add_provider_select_preset)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false }
                    ) {
                        ApiProviderCatalog.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    viewModel.selectPreset(preset)
                                    presetMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::updateDisplayName,
                    label = { Text(stringResource(R.string.add_provider_display_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = { Text(stringResource(R.string.add_provider_base_url_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.selectedPreset?.requiresApiKey == true) {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        label = { Text(stringResource(R.string.add_provider_api_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(onClick = { viewModel.fetchModels() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_provider_fetch_models))
                }

                if (state.isFetchingModels) {
                    CircularProgressIndicator()
                } else if (state.availableModels.isNotEmpty()) {
                    LazyColumn {
                        items(state.availableModels) { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = { viewModel.selectModel(modelId) }
                            )
                        }
                    }
                } else if (state.modelFetchFailed) {
                    OutlinedTextField(
                        value = state.selectedModel,
                        onValueChange = viewModel::selectModel,
                        label = { Text(stringResource(R.string.add_provider_model_fetch_failed)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.save() },
                enabled = state.selectedPreset != null && state.selectedModel.isNotBlank()
            ) {
                Text(stringResource(R.string.add_provider_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_provider_cancel)) }
        }
    )
}
