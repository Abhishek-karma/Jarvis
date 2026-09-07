package com.jarvis.feature.settings

import android.content.Context
import android.os.StatFs
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.ml.InstalledModel
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.LocalModelState
import java.util.Locale


@Composable
fun LocalModelsSection(
    state: LocalModelState,
    installed: List<InstalledModel>,
    catalog: List<LocalModelSpec>,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    onActivate: (String) -> Unit,
    onImport: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        LocalStorageCard()

        Spacer(modifier = Modifier.height(Spacing.xl))


        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Installed models (${installed.size})",
                style = JarvisText.H3,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }


        when (state) {
            is LocalModelState.Downloading -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                TransferCard(
                    title = state.model.displayName,
                    subtitle = "${(state.progress * 100).toInt()}% downloaded",
                    progress = state.progress,
                    onCancel = onCancel,
                )
            }

            is LocalModelState.Importing -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                TransferCard(
                    title = state.model.displayName,
                    subtitle = "Importing… keep the app open",
                    progress = null,
                    onCancel = null,
                )
            }

            is LocalModelState.Error -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = state.message,
                    style = JarvisText.BodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> Unit
        }

        if (installed.isEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "No models installed yet.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(Spacing.sm))
            installed.forEach { model ->
                InstalledModelCard(
                    model = model,
                    onDelete = { onDelete(model.spec.id) },
                    onActivate = { onActivate(model.spec.id) },
                )
                Spacer(modifier = Modifier.height(Spacing.md))
            }
        }


        val installedIds = installed.map { it.spec.id }.toSet()
        val downloadable = catalog.filter { it.id !in installedIds }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Available to download (${downloadable.size})",
                style = JarvisText.H3,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (downloadable.isEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Every catalog model is installed.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(Spacing.sm))
            downloadable.forEach { spec ->
                CatalogModelCard(catalog = catalog, spec = spec, onDownload = { onDownload(spec.id) })
                Spacer(modifier = Modifier.height(Spacing.md))
            }
        }


        ImportRow(onClick = onImport)

        Spacer(modifier = Modifier.height(Spacing.lg))


        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xl),
            )
            Text(
                text = "Local models never leave this phone.\nSupported format: .litertlm weight files.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Local Storage usage card — real device stats above a thin progress bar. */
@Composable
private fun LocalStorageCard() {
    val context = LocalContext.current
    val stats = rememberStorageStats(context)
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
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
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.xxl),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Local Storage",
                    style = JarvisText.ConvTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Models are stored only on this device",
                    style = JarvisText.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { stats.usedFraction.toFloat() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    color = JarvisColors.Accent.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "${formatBytes(stats.usedBytes)} used · ${formatBytes(stats.freeBytes)} available",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(stats.usedFraction * 100).toInt()}%",
                style = JarvisText.H3,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}


@Composable
private fun InstalledModelCard(
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

/** Catalog model card: sparkle icon, name + Popular, meta, description, tags, big download CTA. */
@Composable
private fun CatalogModelCard(
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
private fun ImportRow(onClick: () -> Unit) {
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

/** Green-dot Active pill next to the model name. */
@Composable
private fun ActivePill() {
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

/** Small gray capability chip — the mock's ".litertlm / Local / Lightweight" tags. */
@Composable
private fun TagChip(text: String) {
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

/** Indeterminate/status card shared by Downloading and Importing states. */
@Composable
private fun TransferCard(
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

/** Storage stats derived from the app's files dir volume — models live there. */
private data class StorageStats(
    val usedBytes: Long,
    val freeBytes: Long,
) {
    val totalBytes: Long get() = usedBytes + freeBytes
    val usedFraction: Double get() = if (totalBytes > 0) usedBytes.toDouble() / totalBytes else 0.0
}

@Composable
private fun rememberStorageStats(context: Context): StorageStats =
    remember {
        runCatching {
            val dir = context.filesDir
            val stat = StatFs(dir.path)
            val total = stat.totalBytes
            val free = stat.freeBytes
            StorageStats(usedBytes = total - free, freeBytes = free)
        }.getOrDefault(StorageStats(0, 0))
    }

/** ".litertlm" file-extension chip from the model file name. */
private fun fileNameTag(fileName: String): String {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
    return if (ext.isNotBlank()) ".$ext" else "Model"
}

/** Human byte size, matching the storage card's phrasing. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.0f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.0f MB", mb)
    return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

private const val LIGHT_MODEL_BYTES = 3L * 1024 * 1024 * 1024
