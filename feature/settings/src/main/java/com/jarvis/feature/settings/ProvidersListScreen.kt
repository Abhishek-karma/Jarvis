package com.jarvis.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.StarBorder
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing
import androidx.compose.foundation.layout.size

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


    var selectedTab by rememberSaveable { mutableStateOf(ProviderTab.CLOUD) }


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


            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f).padding(start = Spacing.xs)) {
                    Text(
                        text = "Providers",
                        style = JarvisText.H2,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Manage and use AI models",
                        style = JarvisText.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { }) {
                    Icon(
                        Icons.Outlined.HelpOutline,
                        contentDescription = "Help",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = selectedTab == ProviderTab.CLOUD) {
                FloatingActionButton(onClick = onAddProvider) {
                    Icon(Icons.Default.Add, contentDescription = "Add provider")
                }
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
            return@Scaffold
        }

        val localModelState by viewModel.localModelState.collectAsStateWithLifecycle()
        val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
        val importLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(viewModel::importLocalModel) }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {

            ProviderSegTabs(selected = selectedTab, onSelect = { selectedTab = it })
            LazyColumn(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                    ProviderTab.LOCAL -> {



                        item {
                            LocalModelsSection(
                                state = localModelState,
                                installed = installedModels,
                                catalog = viewModel.localModels,
                                onDownload = viewModel::startLocalModelDownload,
                                onCancel = viewModel::cancelLocalModelDownload,
                                onDelete = viewModel::deleteLocalModel,
                                onActivate = viewModel::activateLocalModel,
                                onImport = { importLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(Spacing.xl)) }
                    }
                    ProviderTab.CLOUD -> {

                        item {
                            JarvisListSection(title = "Cloud") {
                                if (listState.providers.isEmpty()) {
                                    JarvisEmptyState(
                                        title = "No cloud providers yet",
                                        hint = "Tap + to add one — or use the on-device model on the Local tab.",
                                    )
                                } else {
                                    CloudProviderStack(
                                        providers = listState.providers,
                                        onEditProvider = onEditProvider,
                                        onDeleteRequest = { deletingProvider = it },
                                        onSetDefault = { viewModel.setDefault(it.id) },
                                    )
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(Spacing.huge)) }
                    }
                }
            }
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

/** Stack of cloud provider cards inside a [JarvisListSection], separated by 8dp gaps. */
@Composable
private fun ColumnScope.CloudProviderStack(
    providers: List<ProviderConfig>,
    onEditProvider: (String) -> Unit,
    onDeleteRequest: (ProviderConfig) -> Unit,
    onSetDefault: (ProviderConfig) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        providers.forEach { provider ->
            CloudProviderCard(
                provider = provider,
                onClick = { onEditProvider(provider.id) },
                onSetDefault = { onSetDefault(provider) },
                onDelete = { onDeleteRequest(provider) },
            )
        }
    }
}


@Composable
private fun CloudProviderCard(
    provider: ProviderConfig,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = JarvisShapes.card
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        if (provider.isDefault) {
            Box(
                modifier =
                    Modifier
                        .width(Radius.codeInline)
                        .height(Spacing.xxl)
                        .clip(RoundedCornerShape(Radius.codeInline / 2))
                        .background(JarvisColors.Accent.primary),
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
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

/** Trailing ⋮ menu: Set as default (hidden when already default) + Delete. */
@Composable
private fun ProviderOverflowMenu(
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
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
                    onClick = {
                        expanded = false
                        onSetDefault()
                    },
                    dividerBelow = true,
                )
            }
            JarvisDropdownItem(
                text = "Delete",
                destructive = true,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}


private enum class ProviderTab {
    CLOUD,
    LOCAL,
}


@Composable
private fun ProviderSegTabs(
    selected: ProviderTab,
    onSelect: (ProviderTab) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProviderTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(13.dp),
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.background
                    } else {
                        Color.Transparent
                    },
                border =
                    if (isSelected) {
                        BorderStroke(1.dp, JarvisColors.Accent.primary)
                    } else {
                        null
                    },
                modifier =
                    Modifier
                        .weight(1f)

                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                        },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(
                        imageVector =
                            when (tab) {
                                ProviderTab.CLOUD -> Icons.Outlined.Cloud
                                ProviderTab.LOCAL -> Icons.Outlined.Computer
                            },
                        contentDescription = null,
                        tint =
                            if (isSelected) {
                                JarvisColors.Accent.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(Spacing.xl),
                    )
                    Column {
                        Text(
                            text =
                                when (tab) {
                                    ProviderTab.CLOUD -> "Cloud"
                                    ProviderTab.LOCAL -> "On this device"
                                },
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color =
                                if (isSelected) {
                                    JarvisColors.Accent.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Text(
                            text =
                                when (tab) {
                                    ProviderTab.CLOUD -> "Online models"
                                    ProviderTab.LOCAL -> "Local models"
                                },
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

