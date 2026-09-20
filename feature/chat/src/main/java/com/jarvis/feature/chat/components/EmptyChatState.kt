package com.jarvis.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val prompt: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyChatState(
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxl, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "What shall we dive into?",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                if (enabled) {
                    "Ask anything — I can reason, use tools, and work with your providers."
                } else {
                    "Connect a provider in Settings, or use the on-device model."
                },
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
        )
        if (enabled) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                quickActions().forEach { action ->
                    SuggestionChip(
                        action = action,
                        onClick = { onSuggestion(action.prompt) },
                    )
                }
            }
        }
    }
}

private fun quickActions(): List<QuickAction> =
    listOf(
        QuickAction(
            icon = Icons.Default.AutoAwesome,
            label = "My day",
            prompt = "What's on my calendar and schedule today?",
        ),
        QuickAction(
            icon = Icons.Default.EditNote,
            label = "Send message",
            prompt = "Send a text message",
        ),
        QuickAction(
            icon = Icons.Default.Troubleshoot,
            label = "Device",
            prompt = "Check my battery level and device status",
        ),
        QuickAction(
            icon = Icons.Default.Code,
            label = "Web research",
            prompt = "Search the web for today's top tech news",
        ),
    )

@Composable
fun SuggestionChip(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier =
            modifier
                .clip(JarvisShapes.pill)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = action.label,
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
