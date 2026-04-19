package com.opendash.app.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.data.db.MessageEntity
import com.opendash.app.ui.theme.SpeakerBackground
import com.opendash.app.ui.theme.SpeakerSurface
import com.opendash.app.ui.theme.SpeakerSurfaceElevated
import com.opendash.app.ui.theme.SpeakerTextPrimary
import com.opendash.app.ui.theme.SpeakerTextSecondary
import com.opendash.app.ui.theme.SpeakerTextTertiary
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationsScreen(
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsState()
    val expandedId by viewModel.expandedSessionId.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SpeakerBackground),
    ) {
        if (conversations.isEmpty()) {
            Text(
                text = "会話履歴はまだありません",
                style = MaterialTheme.typography.bodyLarge,
                color = SpeakerTextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            return@Box
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "会話履歴",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SpeakerTextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            items(conversations, key = { it.session.id }) { summary ->
                ConversationCard(
                    summary = summary,
                    expanded = expandedId == summary.session.id,
                    onClick = { viewModel.toggleExpanded(summary.session.id) },
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
    summary: ConversationSummary,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) SpeakerSurfaceElevated else SpeakerSurface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(summary.session.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = SpeakerTextSecondary,
                )
                Text(
                    text = "${summary.messageCount}件",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpeakerTextTertiary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = summary.preview.ifBlank { "(メッセージなし)" },
                style = MaterialTheme.typography.bodyMedium,
                color = SpeakerTextPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )

            if (expanded && summary.messages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = SpeakerTextTertiary.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))
                summary.messages.forEach { message ->
                    MessageRow(message)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: MessageEntity) {
    val label = when (message.role) {
        "user" -> "You"
        "assistant" -> "Dash"
        "system" -> "System"
        "tool" -> "Tool"
        else -> message.role
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SpeakerTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyMedium,
            color = SpeakerTextPrimary,
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return fmt.format(Date(millis))
}
