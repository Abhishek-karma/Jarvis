package com.jarvis.feature.settings.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.ml.LocalModelSpec

/** Catalog model card: sparkle icon, name + Popular, meta, description, tags, big download CTA. */
@Composable
fun CatalogModelCard(
    catalog: List<LocalModelSpec>,
    spec: LocalModelSpec,
    onDownload: () -> Unit,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
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
                        Icons.Default.AutoAwesome,
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
                            text = spec.displayName,
                            style = JarvisText.H3,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        if (catalog.indexOf(spec) == 0) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "Popular",
                                    style = JarvisText.Metadata.copy(fontWeight = FontWeight.SemiBold),
                                    color = JarvisColors.Accent.primary,
                                    modifier = Modifier.padding(horizontal = Spacing.mdPlus, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = listOf(spec.approxSizeLabel, spec.ramNote).filter { it.isNotBlank() }.joinToString(" · "),
                        style = JarvisText.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (spec.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = spec.description,
                    style = JarvisText.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spec.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    spec.tags.take(4).forEach { TagChip(it) }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = JarvisColors.Accent.primary,
                        contentColor = JarvisColors.Accent.onPrimary,
                    ),
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.lg),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text("Download ${spec.approxSizeLabel}", style = JarvisText.Button)
            }
        }
    }
}

/** The "Import model file…" row — bordered icon tile, title + subtitle, chevron. */
@Composable
fun ImportRow(onClick: () -> Unit) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
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
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = JarvisColors.Accent.primary,
                    modifier = Modifier.size(Spacing.xxl),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Import model file…",
                    style = JarvisText.ConvTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Add a .litertlm model from your device",
                    style = JarvisText.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Indeterminate/status card shared by Downloading and Importing states. */
@Composable
fun TransferCard(
    title: String,
    subtitle: String,
    progress: Float?,
    onCancel: (() -> Unit)?,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                    color = JarvisColors.Accent.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                    color = JarvisColors.Accent.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }
        }
    }
}
