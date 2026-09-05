package com.jarvis.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.designsystem.JarvisBadge
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersListScreen(
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deletingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

    // One-shot toasts for delete / default / model import & removal.
    LaunchedEffect(Unit) {
        viewModel.listEvents.collect { event ->
            when (event) {
                is ProvidersListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is ProvidersListEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { JarvisSnackbarHost(snackbarHostState) },
        topBar = {
            JarvisHeader(
                title = "Providers",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProvider) {
                Icon(Icons.Default.Add, contentDescription = "Add provider")
            }
        },
    ) { padding ->
        if (listState.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                JarvisScreenLoader(label = "Loading providers…")
            }
        } else {
            val localModelState by viewModel.localModelState.collectAsStateWithLifecycle()
            val importLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(viewModel::importLocalModel) }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                item {
                    LocalModelCard(
                        state = localModelState,
                        models = viewModel.localModels,
                        onDownload = viewModel::startLocalModelDownload,
                        onCancel = viewModel::cancelLocalModelDownload,
                        onDelete = viewModel::deleteLocalModel,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
                if (listState.providers.isEmpty()) {
                    item {
                        JarvisEmptyState(
                            title = "No cloud providers configured",
                            hint = "Tap + to add a cloud LLM — or use the local model above offline.",
                        )
                    }
                } else {
                    items(listState.providers, key = { it.id }) { provider ->
                        ProviderRow(
                            provider = provider,
                            onClick = { onEditProvider(provider.id) },
                            onDelete = { deletingProvider = provider },
                            onSetDefault = { viewModel.setDefault(provider.id) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(Spacing.huge)) } // FAB clearance
            }
        }
    }

    // Deleting a provider also removes its stored API key — confirm first.
    deletingProvider?.let { provider ->
        JarvisConfirmDialog(
            title = "Delete provider",
            message = "This will permanently remove \"${provider.name}\" and its stored API key.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteProvider(provider.id)
                deletingProvider = null
            },
            onDismiss = { deletingProvider = null },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = provider.baseUrl,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            provider.model?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (provider.isDefault) {
            JarvisBadge(text = "Default")
            Spacer(modifier = Modifier.width(Spacing.sm))
        } else {
            IconButton(onClick = onSetDefault) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Set as default",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete provider",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
