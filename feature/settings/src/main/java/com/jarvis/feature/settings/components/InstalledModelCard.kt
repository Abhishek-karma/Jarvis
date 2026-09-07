package com.jarvis.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.ml.InstalledModel

private const val LIGHT_MODEL_BYTES = 3L * 1024 * 1024 * 1024

@Composable
fun InstalledModelCard(
    model: InstalledModel,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = JarvisColors.Accent.primary,
                        modifier = Modifier.size(Spacing.xxl),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = model.spec.displayName,
                            style = JarvisText.H3,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (model.isActive) {
                            ActivePill()
                        }
                    }
                    Text(
                        text =
                            listOf(formatBytes(model.sizeBytes), model.spec.family.takeIf { it.isNotBlank() } ?: "Local model")
                                .joinToString(" · "),
                        style = JarvisText.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TagChip(fileNameTag(model.spec.fileName))
                        TagChip("Local")
                        if (model.sizeBytes < LIGHT_MODEL_BYTES) TagChip("Lightweight")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onActivate, enabled = !model.isActive) {
                    Text("Use", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        JarvisConfirmDialog(
            title = "Delete model",
            message =
                "This permanently removes \"${model.spec.displayName}\" " +
                    "(${formatBytes(model.sizeBytes)}) from this device.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Green-dot Active pill next to the model name. */
@Composable
fun ActivePill() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = JarvisColors.Semantic.success.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.mdPlus, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(JarvisColors.Semantic.success),
            )
            Text(
                "Active",
                style = JarvisText.Metadata.copy(fontWeight = FontWeight.SemiBold),
                color = JarvisColors.Semantic.success,
            )
        }
    }
}

/** Small gray capability chip. */
@Composable
fun TagChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.mdPlus, vertical = 4.dp),
        )
    }
}

private fun fileNameTag(fileName: String): String {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
    return if (ext.isNotBlank()) ".$ext" else "Model"
}
