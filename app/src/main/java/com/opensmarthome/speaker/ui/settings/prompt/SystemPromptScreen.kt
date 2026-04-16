package com.opensmarthome.speaker.ui.settings.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptScreen(
    onBack: () -> Unit,
    viewModel: SystemPromptViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    // Reset saved flag on any edit
    LaunchedEffect(state.saved) {
        // no-op — flag is reset by updatePrompt
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent personality") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "Customize the agent's system prompt. This is baked into every conversation and shapes tone and behavior.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(12.dp))

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::updatePrompt,
                label = { Text(if (state.usingDefault) "Default prompt (editable)" else "Custom prompt") },
                modifier = Modifier
                    .fillMaxWidth()
                    .size(width = 10.dp, height = 300.dp)
                    .fillMaxWidth(),
                minLines = 10,
                maxLines = 20
            )

            Spacer(Modifier.size(8.dp))

            if (state.saved) {
                Text(
                    text = "Saved. Restart a conversation to pick up the change.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.save() }) {
                    Text("Save")
                }
                OutlinedButton(onClick = { viewModel.resetToDefault() }) {
                    Text("Reset to default")
                }
            }
        }
    }
}
