package com.jarvis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawerContent(
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit = {},
    currentConversationId: String? = null,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editingConversation by remember { mutableStateOf<Conversation?>(null) }
    var deletingConversation by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    val sidebarColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val sidebarActive = MaterialTheme.colorScheme.surfaceContainerLow

    ModalDrawerSheet(
        modifier = Modifier.width(322.dp),
        drawerContainerColor = sidebarColor,
        drawerShape = RoundedCornerShape(topEnd = Radius.sheet, bottomEnd = Radius.sheet),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.xl + 4.dp, end = Spacing.xs, top = Spacing.md, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    JarvisMark(size = Spacing.xl)
                    Text(
                        text = "Jarvis",
                        style = JarvisText.ConvTitle.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(Spacing.xxl + Spacing.xxl)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.xl),
                    )
                }
            }

            var searchQuery by remember { mutableStateOf("") }

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
                modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs),
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
                            contentPadding = PaddingValues(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.md),
                        ) {
                            filteredSections.forEach { section ->
                                item(key = "header-${section.group.name}") {
                                    SectionHeader(
                                        label = section.group.label,
                                        background = sidebarColor,
                                    )
                                }
                                items(section.conversations, key = { it.id }) { conversation ->
                                    ConversationRow(
                                        conversation = conversation,
                                        isActive = conversation.id == currentConversationId,
                                        activeColor = sidebarActive,
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
                    start = Spacing.md,
                    end = Spacing.md,
                    top = Spacing.md,
                    bottom = Spacing.smPlus,
                ),
        )
    }
}


@Composable
private fun ConversationRow(
    conversation: Conversation,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs)
                .clip(RoundedCornerShape(Radius.codeBlock))
                .background(if (isActive) activeColor else Color.Transparent)
                .clickable(onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.mdPlus, bottom = Spacing.mdPlus),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (conversation.pinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = JarvisColors.Accent.primary,
                    modifier = Modifier.size(Spacing.md),
                )
            }
            Text(
                text = conversation.title,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ConversationOverflowMenu(
                conversation = conversation,
                onPin = onPin,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
        Text(
            text = formatTimestamp(conversation.updatedAt),
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = if (conversation.pinned) Spacing.xlPlus else Spacing.xs),
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
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(Spacing.xxl + Spacing.xl)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Conversation options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xlPlus),
            )
        }
        JarvisDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },

            minimumWidth = 180.dp,
        ) {
            JarvisDropdownItem(
                text = if (conversation.pinned) "Unpin" else "Pin",
                onClick = {
                    expanded = false
                    onPin()
                },
                dividerBelow = true,
            )
            JarvisDropdownItem(
                text = "Rename",
                onClick = {
                    expanded = false
                    onRename()
                },
                dividerBelow = true,
            )
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

            val formatter =
                java.time.format.DateTimeFormatter
                    .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                    .withLocale(java.util.Locale.getDefault())
            formatter.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
        }
    }
}

/** Solid-ink "New chat" bar — the HTML .newchat: filled button, 50dp, 14dp radius. */
@Composable
private fun NewChatButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lgPlus, vertical = Spacing.smPlus)
                .clip(RoundedCornerShape(Radius.card))
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(Spacing.xl),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "New chat",
                style = JarvisText.Button,
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

/** The HTML .search — a surface-fill 12dp-radius field with a leading glyph. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Radius.codeBlock),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.mdPlus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.lgPlus),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = JarvisText.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(JarvisColors.Accent.primary),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.sm, end = Spacing.xs),
                decorationBox = { innerField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "Search chats",
                                style = JarvisText.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerField()
                    }
                },
            )
            if (value.isNotEmpty()) {


                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
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


            val canSave = text.isNotBlank() && text.trim() != initialTitle
            TextButton(onClick = onSave, enabled = canSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
