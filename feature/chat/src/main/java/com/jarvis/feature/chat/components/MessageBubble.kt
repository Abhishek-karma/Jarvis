package com.jarvis.feature.chat.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.chat.MarkdownText
import com.jarvis.feature.chat.RouteBadge
import kotlinx.coroutines.delay

private const val COPY_CONFIRM_MS = 1200L
private val ACTION_GLYPH = 16.dp
private val ACTION_HIT = 36.dp
private val ACTION_GAP = 2.dp

@Composable
fun MessageBubble(
    message: Message,
    isPlayingAudio: Boolean = false,
    isLastAssistant: Boolean = false,
    canRegenerate: Boolean = false,
    routeBadge: RouteBadge? = null,
    onSpeak: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onRetry: (String) -> Unit = {},
    onEdit: (Message) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
) {
    val isUser = message.role == MessageRole.USER

    if (isUser) {
        var showActions by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(COPY_CONFIRM_MS)
                copied = false
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            BoxWithConstraints {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = JarvisText.Body,
                        color = MaterialTheme.colorScheme.onBackground,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                        modifier =
                            Modifier
                                .widthIn(max = maxWidth * 0.82f)
                                .clip(JarvisBubbleShapes.user)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { showActions = !showActions }
                                .padding(horizontal = Spacing.lg, vertical = Spacing.mdPlus),
                    )
                }
            }

            if (showActions) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            copied = true
                        },
                        modifier = Modifier.size(ACTION_HIT),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = if (copied) "Copied" else "Copy message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ACTION_GLYPH),
                        )
                    }
                    IconButton(
                        onClick = { onEdit(message) },
                        modifier = Modifier.size(ACTION_HIT),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ACTION_GLYPH),
                        )
                    }
                    IconButton(
                        onClick = { onDelete(message.id) },
                        modifier = Modifier.size(ACTION_HIT),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ACTION_GLYPH),
                        )
                    }
                }
            }
        }
        return
    }

    if (message.role == MessageRole.TOOL) {
        if (message.status != MessageStatus.COMPLETE) return
        val cleanContent = message.content.removePrefix("✓").trim()
        if (cleanContent.isBlank() ||
            cleanContent.equals("Done", ignoreCase = true) ||
            cleanContent.equals("Completed", ignoreCase = true) ||
            cleanContent.equals("Success", ignoreCase = true)
        ) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics {
                    contentDescription = "Action milestone: $cleanContent"
                },
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = cleanContent,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (message.content.isNotEmpty()) {
            SelectionContainer {
                MarkdownText(markdown = message.content)
            }
        }

        when (message.status) {
            MessageStatus.STREAMING -> {
                // Live streaming output
            }

            MessageStatus.ERROR -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        text = "Something went wrong",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (canRegenerate) {
                        Text(
                            text = "·",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Try again",
                            style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(JarvisShapes.chip)
                                .clickable { onRetry(message.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            MessageStatus.STOPPED -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        text = "Stopped",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (canRegenerate) {
                        Text(
                            text = "·",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Continue",
                            style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(JarvisShapes.chip)
                                .clickable(onClick = onContinue)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                AssistantActionRow(
                    message = message,
                    isPlayingAudio = isPlayingAudio,
                    showRegenerate = isLastAssistant && canRegenerate,
                    onSpeak = onSpeak,
                    onStopSpeaking = onStopSpeaking,
                    onRegenerate = onRegenerate,
                    onDelete = { onDelete(message.id) },
                )
            }

            MessageStatus.COMPLETE -> {
                if (message.content.isNotEmpty()) {
                    AssistantActionRow(
                        message = message,
                        isPlayingAudio = isPlayingAudio,
                        showRegenerate = isLastAssistant && canRegenerate,
                        onSpeak = onSpeak,
                        onStopSpeaking = onStopSpeaking,
                        onRegenerate = onRegenerate,
                        onDelete = { onDelete(message.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantActionRow(
    message: Message,
    isPlayingAudio: Boolean,
    showRegenerate: Boolean,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CONFIRM_MS)
            copied = false
        }
    }

    val context = LocalContext.current
    fun shareResponse() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message.content)
            }
        context.startActivity(Intent.createChooser(intent, "Share response"))
    }

    var showMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
        modifier = Modifier.padding(top = Spacing.xs),
    ) {
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(message.content))
                copied = true
            },
            modifier = Modifier.size(ACTION_HIT),
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy response",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_GLYPH),
            )
        }

        IconButton(
            onClick = { if (isPlayingAudio) onStopSpeaking() else onSpeak() },
            modifier = Modifier.size(ACTION_HIT),
        ) {
            Icon(
                imageVector =
                    if (isPlayingAudio) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                contentDescription = if (isPlayingAudio) "Stop speaking" else "Read aloud",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_GLYPH),
            )
        }

        if (showRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(ACTION_HIT)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Regenerate response",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ACTION_GLYPH),
                )
            }
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(ACTION_HIT),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ACTION_GLYPH),
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Share", style = JarvisText.BodyMedium) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        showMenu = false
                        shareResponse()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", style = JarvisText.BodyMedium) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

