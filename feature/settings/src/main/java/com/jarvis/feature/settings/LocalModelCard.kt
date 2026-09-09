package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.ml.InstalledModel
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.LocalModelState
import com.jarvis.feature.settings.components.CatalogModelCard
import com.jarvis.feature.settings.components.ImportRow
import com.jarvis.feature.settings.components.InstalledModelCard
import com.jarvis.feature.settings.components.LocalStorageCard
import com.jarvis.feature.settings.components.TransferCard

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
    val installedIds = installed.map { it.spec.id }.toSet()
    val downloadable = catalog.filter { it.id !in installedIds }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Section 1: Active & Installed Local Models + Storage status
        JarvisListSection(title = "INSTALLED ON-DEVICE MODELS (${installed.size})") {
            LocalStorageCard()

            when (state) {
                is LocalModelState.Downloading -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TransferCard(
                        title = state.model.displayName,
                        subtitle = "${(state.progress * 100).toInt()}% downloaded",
                        progress = state.progress,
                        onCancel = onCancel,
                    )
                }

                is LocalModelState.Importing -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TransferCard(
                        title = state.model.displayName,
                        subtitle = "Importing… keep the app open",
                        progress = null,
                        onCancel = null,
                    )
                }

                is LocalModelState.Error -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = state.message,
                        style = JarvisText.BodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }

                else -> Unit
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (installed.isEmpty()) {
                JarvisEmptyState(
                    title = "No on-device models installed",
                    hint = "Download a curated model below or import your own .litertlm file for 100% offline inference.",
                    icon = Icons.Outlined.Computer,
                )
            } else {
                installed.forEachIndexed { index, model ->
                    InstalledModelCard(
                        model = model,
                        onDelete = { onDelete(model.spec.id) },
                        onActivate = { onActivate(model.spec.id) },
                    )
                    if (index < installed.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }
        }

        // Section 2: Catalog & Custom Import
        JarvisListSection(title = "EXPLORE CATALOG & IMPORT (${downloadable.size})") {
            if (downloadable.isEmpty()) {
                JarvisEmptyState(
                    title = "All catalog models installed",
                    hint = "You have downloaded every curated local model.",
                    icon = Icons.Outlined.Download,
                )
            } else {
                downloadable.forEachIndexed { index, spec ->
                    CatalogModelCard(
                        catalog = catalog,
                        spec = spec,
                        onDownload = { onDownload(spec.id) },
                    )
                    if (index < downloadable.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ImportRow(onClick = onImport)

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.xl),
                )
                Column {
                    Text(
                        text = "100% On-Device Privacy Guarantee",
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Local model weights execute directly in device RAM. No conversations or telemetry ever leave your phone.",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

