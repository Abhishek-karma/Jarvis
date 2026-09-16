package com.jarvis.feature.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersListScreen(
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    initialTab: String = "cloud",
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deletingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

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
                title = "Model Providers",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("providers_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onAddProvider,
                        modifier = Modifier.testTag("providers_add_action"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Provider",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProvider,
                modifier = Modifier.testTag("providers_add_fab"),
                containerColor = JarvisColors.Accent.primary,
                contentColor = JarvisColors.Accent.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
            ) {
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
                JarvisScreenLoader(label = "Loading providers…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                JarvisListSection(title = "CLOUD AI PROVIDERS (${listState.providers.size})") {
                    if (listState.providers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            JarvisEmptyState(
                                title = "No cloud providers configured",
                                hint = "Connect OpenAI, Claude, Gemini, Mistral, or custom OpenAI-compatible endpoints to start chatting.",
                                icon = Icons.Outlined.Cloud,
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Button(
                                onClick = onAddProvider,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = JarvisColors.Accent.primary,
                                    contentColor = JarvisColors.Accent.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Add Cloud Provider", style = JarvisText.Button)
                            }
                        }
                    } else {
                        listState.providers.forEachIndexed { index, provider ->
                            CloudProviderRow(
                                provider = provider,
                                onClick = { onEditProvider(provider.id) },
                                onSetDefault = { viewModel.setDefault(provider.id) },
                                onDelete = { deletingProvider = provider },
                            )
                            if (index < listState.providers.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onAddProvider)
                                .padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            JarvisIconTile(
                                icon = Icons.Outlined.Add,
                                tinted = true,
                            )
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Add Another Provider",
                                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Connect additional models or private LLM endpoints",
                                    style = JarvisText.Metadata,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.xxl)) }
        }
    }

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
private fun CloudProviderRow(
    provider: ProviderConfig,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarvisIconTile(
            icon = Icons.Outlined.Cloud,
            tinted = provider.isDefault,
        )
        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = provider.name,
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (provider.isDefault) {
                    Surface(
                        shape = JarvisShapes.pill,
                        color = JarvisColors.Accent.primarySoft,
                    ) {
                        Text(
                            text = "DEFAULT",
                            style = JarvisText.Metadata.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            ),
                            color = JarvisColors.Accent.primary,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            provider.model?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = provider.baseUrl,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ProviderOverflowMenu(
            isDefault = provider.isDefault,
            onSetDefault = onSetDefault,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun ProviderOverflowMenu(
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("provider_overflow_button"),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xl),
            )
        }
        JarvisDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minimumWidth = 200.dp,
        ) {
            if (!isDefault) {
                JarvisDropdownItem(
                    text = "Set as default",
                    leadingIcon = Icons.Outlined.Star,
                    onClick = {
                        expanded = false
                        onSetDefault()
                    },
                    dividerBelow = true,
                )
            }
            JarvisDropdownItem(
                text = "Delete",
                leadingIcon = Icons.Outlined.Delete,
                destructive = true,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
