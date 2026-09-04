package com.jarvis.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import com.jarvis.core.common.Conversation
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawerContent(
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editingConversation by remember { mutableStateOf<Conversation?>(null) }
    var deletingConversation by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Sidebar is always darker than the canvas — ChatGPT's signature treatment.
    val sidebarColor = if (isSystemInDarkTheme()) {
        JarvisColors.Dark.sidebar
    } else {
        JarvisColors.Light.sidebar
    }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = sidebarColor,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Jarvis", style = JarvisText.ConvTitle) },
                    actions = {
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Default.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = sidebarColor,
                    ),
                )
            },
            containerColor = sidebarColor,
        ) { padding ->
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (uiState.sections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No conversations yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Start a new chat to begin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    uiState.sections.forEach { section ->
                        stickyHeader {
                            SectionHeader(label = section.group.label)
                        }
                        items(section.conversations, key = { it.id }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
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

    // ── Rename Dialog ──────────────────────────────────────────────────────────

    editingConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { editingConversation = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(conversation, renameText)
                    editingConversation = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingConversation = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Delete Confirmation Dialog ─────────────────────────────────────────────

    deletingConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deletingConversation = null },
            title = { Text("Delete conversation") },
            text = { Text("This will permanently delete \"${conversation.title}\" and all its messages.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(conversation)
                    deletingConversation = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingConversation = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Internal Composables ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    // Uppercase letterspaced group header — "Today / Yesterday / Previous 7 Days".
    Text(
        text = label.uppercase(),
        style = JarvisText.SectionHeader,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showActions = !showActions },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp), // ~72dp row (spec §8)
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .height(14.dp)
                            .width(14.dp),
                    )
                }
                Text(
                    text = conversation.title,
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatTimestamp(conversation.updatedAt),
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showActions) {
            IconButton(onClick = { onPin(); showActions = false }) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (conversation.pinned) "Unpin" else "Pin",
                    tint = if (conversation.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRename(); showActions = false }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Rename",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onDelete(); showActions = false }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
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
