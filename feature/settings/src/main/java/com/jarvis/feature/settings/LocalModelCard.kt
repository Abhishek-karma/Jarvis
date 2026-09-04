package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.ml.LocalModelSpec
import com.jarvis.core.ml.LocalModelState

/**
 * On-device model management card shown atop the Providers screen: status, one-tap download
 * with progress, remove, and the manual/fallback copy when the download is gated (Gemma license).
 */
@Composable
fun LocalModelCard(
    state: LocalModelState,
    models: List<LocalModelSpec>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit = {},
) {
    val spec = models.firstOrNull() ?: return

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "On-device model",
                        style = JarvisText.Body.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Runs offline on this phone for the Local route",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = spec.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(spec.approxSizeLabel, spec.ramNote).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is LocalModelState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Installed. Local routing is available.",
                            style = JarvisText.Body,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDelete) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                is LocalModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(state.progress * 100).toInt()}% downloaded",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }

                is LocalModelState.Importing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Importing ${state.model.displayName}…",
                        style = JarvisText.Body,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Copying from storage. A multi-GB model can take a few minutes, so keep " +
                            "the app open until it finishes.",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LocalModelState.Error -> {
                    Text(
                        text = state.message,
                        style = JarvisText.Body,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // A failed import must not dead-end: offer the picker again alongside
                    // retrying the download, like the not-downloaded state.
                    Button(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import from storage")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download ${spec.displayName}")
                    }
                }

                else -> {
                    // NotDownloaded / None
                    Text(
                        text =
                            if (spec.license.isNotBlank()) {
                                "Accept ${spec.license} to download. This is a large download, so use Wi-Fi."
                            } else {
                                "This is a large download, so use Wi-Fi."
                            },
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download ${spec.displayName}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import from storage")
                    }
                }
            }
        }
    }
}
