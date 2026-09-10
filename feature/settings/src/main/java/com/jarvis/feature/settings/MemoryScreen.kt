package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            JarvisHeader(
                title = "Assistant Memory",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("memory_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (state.memories.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val json = viewModel.exportMemoriesJson()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Jarvis Memories", json)
                                clipboard?.setPrimaryClip(clip)
                                showExportDialog = true
                            },
                            modifier = Modifier.testTag("memory_export_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Export memory",
                            )
                        }
                        IconButton(
                            onClick = { viewModel.setShowClearAllDialog(true) },
                            modifier = Modifier.testTag("memory_clear_all_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear all memory",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.setShowAddDialog(true) },
                modifier = Modifier.testTag("memory_add_fab"),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add memory")
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        JarvisIconTile(Icons.Outlined.Psychology)
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Enable Memory",
                                style = JarvisText.Body,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "When enabled, Jarvis learns your preferences and injects relevant context into conversations.",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.memoryEnabled,
                            onCheckedChange = { viewModel.setMemoryEnabled(it) },
                            modifier = Modifier.testTag("memory_enabled_switch"),
                        )
                    }
                }
            }

            if (state.memoryEnabled) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search memories...") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_search_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedCategory == null,
                                onClick = { viewModel.setCategoryFilter(null) },
                                label = { Text("All (${state.memories.size})") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedCategory == MemoryCategory.LONG_TERM_FACT,
                                onClick = { viewModel.setCategoryFilter(MemoryCategory.LONG_TERM_FACT) },
                                label = { Text("Preferences & Facts") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedCategory == MemoryCategory.CONVERSATION_CONTEXT,
                                onClick = { viewModel.setCategoryFilter(MemoryCategory.CONVERSATION_CONTEXT) },
                                label = { Text("Context") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedCategory == MemoryCategory.EPISODIC,
                                onClick = { viewModel.setCategoryFilter(MemoryCategory.EPISODIC) },
                                label = { Text("Events") },
                            )
                        }
                    }
                }

                if (state.filteredMemories.isEmpty()) {
                    item {
                        JarvisEmptyState(
                            title = if (state.searchQuery.isNotBlank()) "No matching memories" else "No memories yet",
                            hint = if (state.searchQuery.isNotBlank()) "Try a different search term" else "Jarvis will automatically record important facts here, or tap + to add one.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xxl),
                        )
                    }
                } else {
                    items(state.filteredMemories, key = { it.id }) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onEdit = { viewModel.setEditingMemory(memory) },
                            onDelete = { viewModel.deleteMemory(memory.id) },
                        )
                    }
                }
            } else {
                item {
                    JarvisEmptyState(
                        title = "Memory is disabled",
                        hint = "Jarvis will not retain or use any stored memory across chat sessions.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xxl),
                    )
                }
            }
        }
    }

    if (state.showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowClearAllDialog(false) },
            title = { Text("Clear all memories?") },
            text = { Text("This will permanently remove all stored preferences, facts, and conversation context. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAllMemories() },
                    modifier = Modifier.testTag("confirm_clear_all_memory"),
                ) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowClearAllDialog(false) }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Memories Exported") },
            text = { Text("${state.memories.size} memories have been formatted as JSON and copied to your clipboard.") },
            confirmButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    modifier = Modifier.testTag("dismiss_export_dialog"),
                ) {
                    Text("OK")
                }
            },
        )
    }

    if (state.showAddDialog || state.editingMemory != null) {
        val memoryToEdit = state.editingMemory
        MemoryEditDialog(
            initialCategory = memoryToEdit?.category ?: (state.selectedCategory ?: MemoryCategory.LONG_TERM_FACT),
            initialContent = memoryToEdit?.content.orEmpty(),
            initialIsPrivate = memoryToEdit?.isPrivate ?: false,
            isEditing = memoryToEdit != null,
            onDismiss = {
                viewModel.setShowAddDialog(false)
                viewModel.setEditingMemory(null)
            },
            onSave = { category, content, isPrivate ->
                viewModel.saveMemory(category, content, isPrivate)
            },
        )
    }
}

@Composable
private fun MemoryItemCard(
    memory: Memory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_item_${memory.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val categoryLabel = when (memory.category) {
                    MemoryCategory.LONG_TERM_FACT -> "Preference"
                    MemoryCategory.CONVERSATION_CONTEXT -> "Context"
                    MemoryCategory.EPISODIC -> "Event"
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = categoryLabel,
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                if (memory.isPrivate) {
                    Spacer(Modifier.width(Spacing.xs))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.height(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "Local only",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                val dateStr = remember(memory.timestamp) {
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(memory.timestamp))
                }
                Text(
                    text = dateStr,
                    style = JarvisText.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_memory_${memory.id}"),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_memory_${memory.id}"),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text = memory.content,
                style = JarvisText.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (memory.source.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Source: ${memory.source}",
                    style = JarvisText.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initialCategory: MemoryCategory,
    initialContent: String,
    initialIsPrivate: Boolean,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (category: MemoryCategory, content: String, isPrivate: Boolean) -> Unit,
) {
    var category by remember { mutableStateOf(initialCategory) }
    var content by remember { mutableStateOf(initialContent) }
    var isPrivate by remember { mutableStateOf(initialIsPrivate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Memory" else "Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Category", style = JarvisText.Metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(
                        selected = category == MemoryCategory.LONG_TERM_FACT,
                        onClick = { category = MemoryCategory.LONG_TERM_FACT },
                        label = { Text("Preference") },
                    )
                    FilterChip(
                        selected = category == MemoryCategory.CONVERSATION_CONTEXT,
                        onClick = { category = MemoryCategory.CONVERSATION_CONTEXT },
                        label = { Text("Context") },
                    )
                    FilterChip(
                        selected = category == MemoryCategory.EPISODIC,
                        onClick = { category = MemoryCategory.EPISODIC },
                        label = { Text("Event") },
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Memory details") },
                    placeholder = { Text("e.g. User prefers concise answers without emoji.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_content_input"),
                    minLines = 3,
                    maxLines = 6,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Private (Local only)", style = JarvisText.Body)
                        Text(
                            "Never sent to cloud AI providers. Kept strictly on-device.",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                        modifier = Modifier.testTag("memory_privacy_switch"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) {
                        onSave(category, content, isPrivate)
                    }
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.testTag("save_memory_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
