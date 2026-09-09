package com.jarvis.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.sp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.ml.LocalModelSpec

/** Catalog model card: sparkle icon tile, name + Popular, meta, description, tags, download button. */
@Composable
fun CatalogModelCard(
    catalog: List<LocalModelSpec>,
    spec: LocalModelSpec,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.padding(Spacing.lg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            JarvisIconTile(
                icon = Icons.Default.AutoAwesome,
                tinted = true,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = spec.displayName,
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (catalog.indexOf(spec) == 0) {
                        Surface(
                            shape = JarvisShapes.pill,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "POPULAR",
                                style = JarvisText.Metadata.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                ),
                                color = JarvisColors.Accent.primary,
                                modifier = Modifier.padding(horizontal = Spacing.smPlus, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = listOf(spec.approxSizeLabel, spec.ramNote).filter { it.isNotBlank() }.joinToString(" · "),
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (spec.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = spec.description,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (spec.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                spec.tags.take(4).forEach { TagChip(it) }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            shape = RoundedCornerShape(12.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = JarvisColors.Accent.primary,
                    contentColor = JarvisColors.Accent.onPrimary,
                ),
        ) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text("Download ${spec.approxSizeLabel}", style = JarvisText.Button)
        }
    }
}

/** The "Import model file…" row — bordered icon tile, title + subtitle, chevron. */
@Composable
fun ImportRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        JarvisIconTile(
            icon = Icons.Outlined.Description,
            tinted = false,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Import model file…",
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Add a .litertlm model from device storage",
                style = JarvisText.Metadata,
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

/** Indeterminate/status card shared by Downloading and Importing states. */
@Composable
fun TransferCard(
    title: String,
    subtitle: String,
    progress: Float?,
    onCancel: (() -> Unit)?,
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
                TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
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
