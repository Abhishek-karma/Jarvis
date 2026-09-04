package com.jarvis.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersListScreen(
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val localModelState by viewModel.localModelState.collectAsStateWithLifecycle()
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(viewModel::importLocalModel) }
            LazyColumn(
                modifier = Modifier
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "No cloud providers configured",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tap + to add a cloud LLM — or use the local model above offline.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(listState.providers, key = { it.id }) { provider ->
                        ProviderRow(
                            provider = provider,
                            onClick = { onEditProvider(provider.id) },
                            onDelete = { viewModel.deleteProvider(provider.id) },
                            onSetDefault = { viewModel.setDefault(provider.id) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) } // FAB clearance
            }
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = com.jarvis.core.designsystem.JarvisText.Body.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = provider.baseUrl,
                style = com.jarvis.core.designsystem.JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            provider.model?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (provider.isDefault) {
            Text(
                "Default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (!provider.isDefault) {
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
