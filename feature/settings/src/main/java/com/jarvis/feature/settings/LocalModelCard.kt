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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
