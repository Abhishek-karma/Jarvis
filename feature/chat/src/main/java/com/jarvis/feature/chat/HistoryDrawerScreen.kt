package com.jarvis.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.Conversation
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawerContent(
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    currentConversationId: String? = null,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editingConversation by remember { mutableStateOf<Conversation?>(null) }
    var deletingConversation by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Sidebar is always darker than the canvas — ChatGPT's signature treatment.
    val sidebarColor =
        if (isSystemInDarkTheme()) {
            JarvisColors.Dark.sidebar
        } else {
            JarvisColors.Light.sidebar
        }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = sidebarColor,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            JarvisHeader(
                title = "Jarvis",
                containerColor = sidebarColor,
            )

            var searchQuery by remember { mutableStateOf("") }

            // Client-side filter by title, grouped sections preserved.
            val filteredSections =
                remember(uiState.sections, searchQuery) {
                    if (searchQuery.isBlank()) {
                        uiState.sections
                    } else {
                        uiState.sections.mapNotNull { section ->
                            val matches =
                                section.conversations.filter {
                                    it.title.contains(searchQuery.trim(), ignoreCase = true)
                                }
                            if (matches.isEmpty()) null else section.copy(conversations = matches)
                        }
                    }
                }

            NewChatButton(onClick = onNewChat)
            SearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            JarvisScreenLoader()
                        }

                    filteredSections.isEmpty() && searchQuery.isNotBlank() ->
                        JarvisEmptyState(
                            title = "No conversations found",
                            hint = "Try a different search",
                        )

                    filteredSections.isEmpty() ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            JarvisMark(size = Spacing.xxl)
                            Spacer(modifier = Modifier.height(Spacing.md))
                            JarvisEmptyState(
                                title = "No conversations yet",
                                hint = "Tap “New chat” to begin",
                                modifier = Modifier.padding(vertical = Spacing.sm),
                            )
                        }

                    else ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.lg),
                        ) {
                            filteredSections.forEach { section ->
                                stickyHeader(key = "header-${section.group.name}") {
                                    // Rows scroll under the sticky label — give it the
                                    // sidebar's opaque color so text doesn't bleed through.
                                    SectionHeader(
                                        label = section.group.label,
                                        background = sidebarColor,
                                    )
                                }
                                items(section.conversations, key = { it.id }) { conversation ->
                                    ConversationRow(
                                        conversation = conversation,
                                        isActive = conversation.id == currentConversationId,
                                        onClick = { onOpenConversation(conversation.id) },
                                        onPin = { viewModel.togglePin(conversation) },
                                        onRename = {
                                            editingConversation = conversation
                                            renameText = conversation.title
                                        },
                                        onDelete = { deletingConversation = conversation },
                                    )
                                }
                            }
                        }
                }
            }

            SettingsRow(onClick = onOpenSettings)
        }
    }

    editingConversation?.let { conversation ->
        RenameConversationDialog(
            initialTitle = conversation.title,
            onSave = {
                viewModel.rename(conversation, renameText)
                editingConversation = null
            },
            onDismiss = { editingConversation = null },
            onTextChange = { renameText = it },
        )
    }

    deletingConversation?.let { conversation ->
        JarvisConfirmDialog(
            title = "Delete conversation",
            message = "This will permanently delete \"${conversation.title}\" and all its messages.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(conversation)
                deletingConversation = null
            },
            onDismiss = { deletingConversation = null },
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    background: Color,
) {
    Surface(color = background, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = JarvisText.SectionHeader,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.sm,
                    bottom = Spacing.sm,
                ),
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sidebarActive =
        if (isSystemInDarkTheme()) {
            JarvisColors.Dark.sidebarActive
        } else {
            JarvisColors.Light.sidebarActive
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                .clip(RoundedCornerShape(Radius.small))
                .background(if (isActive) sidebarActive else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.weight(1f),
        ) {
            if (conversation.pinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = JarvisColors.Accent.orange,
                    modifier = Modifier.size(Spacing.md),
                )
            }
            Text(
                text = conversation.title,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = formatTimestamp(conversation.updatedAt),
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )

        // Overflow menu — one affordance, standard pattern, keeps the row quiet.
        ConversationOverflowMenu(
            conversation = conversation,
            onPin = onPin,
            onRename = onRename,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun ConversationOverflowMenu(
    conversation: Conversation,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(Spacing.xxl)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Conversation options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xl),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (conversation.pinned) "Unpin" else "Pin") },
                onClick = {
                    expanded = false
                    onPin()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        tint = if (conversation.pinned) JarvisColors.Accent.orange else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.lg),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    expanded = false
                    onRename()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.lg),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Spacing.lg),
                    )
                },
            )
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(millis))
        }
    }
}

/** Full-width "+ New chat" pill at the top of the sidebar (ChatGPT/Claude sidebar spec). */
@Composable
private fun NewChatButton(onClick: () -> Unit) {
    Surface(
        shape = JarvisShapes.codeBlock,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .clip(JarvisShapes.codeBlock)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(Spacing.xl),
            )
            Text(
                text = "New chat",
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Rounded search pill that filters conversation titles client-side. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.md),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = JarvisText.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(JarvisColors.Accent.orange),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.sm, end = Spacing.xs),
                decorationBox = { innerField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "Search conversations",
                                style = JarvisText.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerField()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(Spacing.xxl)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.md),
                    )
                }
            }
        }
    }
}

/** Settings pinned to the drawer bottom (04-DESIGN.md Screen 2: "Bottom: … Settings"). */
@Composable
private fun SettingsRow(onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.xl),
        )
        Text(
            text = "Settings",
            style = JarvisText.Body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.xl),
        )
    }
}

/** Rename dialog for a conversation title. */
@Composable
private fun RenameConversationDialog(
    initialTitle: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation", style = JarvisText.ConvTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                label = { Text("Title") },
                singleLine = true,
                shape = JarvisShapes.input,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
