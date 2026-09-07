package com.jarvis.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val prompt: String,
)

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
            text = "Hi, I'm Jarvis.",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "What shall we dive into?",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                if (enabled) {
                    "Ask anything — I can reason, run tools, and work with your providers."
                } else {
                    "Connect a provider in Settings, or use the on-device model."
                },
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
        )
        if (enabled) {
            quickActions().chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.mdPlus),
                ) {
                    rowActions.forEach { action ->
                        QuickActionCard(
                            action = action,
                            onClick = { onSuggestion(action.prompt) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (rowActions.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.mdPlus))
            }
        }
    }
}

private fun quickActions(): List<QuickAction> =
    listOf(
        QuickAction(
            icon = Icons.Default.Code,
            label = "Explain code",
            description = "Paste a snippet, get a plain-language walkthrough.",
            prompt = "Explain this code",
        ),
        QuickAction(
            icon = Icons.Default.AutoAwesome,
            label = "Plan morning",
            description = "One priority, two small wins, then inbox.",
            prompt = "Plan my morning",
        ),
        QuickAction(
            icon = Icons.Default.EditNote,
            label = "Draft reply",
            description = "Sharp, warm, ready to send.",
            prompt = "Draft a reply",
        ),
        QuickAction(
            icon = Icons.Default.Troubleshoot,
            label = "Debug",
            description = "Find what's going wrong and why.",
            prompt = "Debug this error",
        ),
    )

@Composable
fun QuickActionCard(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            modifier
                .clip(JarvisShapes.card)
                .clickable(onClick = onClick, role = Role.Button),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 104.dp)
                    .padding(Spacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(JarvisShapes.codeBlock)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.xlPlus),
                )
            }
            Text(
                text = action.label,
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = action.description,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
