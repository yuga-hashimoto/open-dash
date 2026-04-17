package com.opensmarthome.speaker.ui.ambient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opensmarthome.speaker.tool.system.TimerInfo

/**
 * Echo Show-style "active timers" card. Renders one row per active timer
 * with its label (if any), live mm:ss countdown, and a Close button that
 * invokes [onCancelTimer]. Renders nothing when [timers] is empty — the
 * caller is expected to include/exclude this composable based on the same
 * list so we don't leak an empty card into the layout.
 */
@Composable
fun ActiveTimersCard(
    timers: List<TimerInfo>,
    onCancelTimer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (timers.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Active timers",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            timers.forEach { timer ->
                TimerRow(
                    timer = timer,
                    onCancel = { onCancelTimer(timer.id) }
                )
            }
        }
    }
}

@Composable
private fun TimerRow(
    timer: TimerInfo,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val label = timer.label.takeIf { it.isNotBlank() }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            Text(
                text = formatRemaining(timer.remainingSeconds),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel timer ${timer.label.ifBlank { timer.id }}"
            )
        }
    }
}

/**
 * Format remaining seconds as mm:ss (or h:mm:ss when >= 1 hour).
 * Public so the call site can share one formatter with tests.
 */
internal fun formatRemaining(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3_600
    val minutes = (safe % 3_600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ActiveTimersCardPreview() {
    ActiveTimersCard(
        timers = listOf(
            TimerInfo(id = "t1", label = "pasta", remainingSeconds = 185, totalSeconds = 300),
            TimerInfo(id = "t2", label = "", remainingSeconds = 5_400, totalSeconds = 7_200)
        ),
        onCancelTimer = {}
    )
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ActiveTimersCardEmptyPreview() {
    // Empty list — composable returns early, so preview renders blank by design.
    ActiveTimersCard(timers = emptyList(), onCancelTimer = {})
}
