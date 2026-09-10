package com.jarvis.feature.chat.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.designsystem.StreamingCursor
import com.jarvis.feature.chat.MarkdownText
import com.jarvis.feature.chat.RouteBadge
import kotlinx.coroutines.delay

private const val COPY_CONFIRM_MS = 1200L
private val ACTION_GLYPH = 16.dp
private val ACTION_HIT = 40.dp
private val ACTION_GAP = 4.dp

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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector =
                    if (message.status == MessageStatus.ERROR) {
                        Icons.Default.PriorityHigh
                    } else {
                        Icons.Default.Check
                    },
                contentDescription = null,
                tint =
                    if (message.status == MessageStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        JarvisColors.Accent.primary
                    },
                modifier = Modifier.size(Spacing.lg),
            )
            Text(
                text = message.content,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            JarvisMark(size = Spacing.lg + Spacing.xs)
            Text(
                text = "Jarvis",
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
            routeBadge?.let {
                Text(
                    text = routeLabelForBadge(it),
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            var reasoningExpanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xs)
                    .clip(JarvisShapes.card)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { reasoningExpanded = !reasoningExpanded }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Reasoned",
                        style = JarvisText.SenderLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (reasoningExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (reasoningExpanded) "Collapse reasoning" else "Expand reasoning",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (reasoningExpanded) {
                    Spacer(Modifier.height(Spacing.xs))
                    SelectionContainer {
                        Text(
                            text = reasoning,
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = reasoning,
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (message.content.isNotEmpty()) {
            SelectionContainer {
                MarkdownText(markdown = message.content)
            }
        }

        when (message.status) {
            MessageStatus.STREAMING ->
                if (message.content.isEmpty()) {
                    StreamingCursor()
                } else {
                    StreamingCursor(Modifier.padding(top = Spacing.xs))
                }

            MessageStatus.ERROR -> {
                Surface(
                    shape = JarvisShapes.card,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(Spacing.md),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PriorityHigh,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Spacing.xl),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unable to complete response",
                                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = message.errorHint ?: "An unexpected error occurred while generating.",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val isPermissionError = message.errorHint?.contains("permission", ignoreCase = true) == true
                        if (isPermissionError) {
                            TextButton(onClick = onOpenPermissions) {
                                Text("Permissions")
                            }
                        }
                        if (canRegenerate) {
                            TextButton(onClick = { onRetry(message.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(Spacing.md),
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text("Retry")
                            }
                        }
                        IconButton(onClick = { onDelete(message.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete error",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Spacing.lg),
                            )
                        }
                    }
                }
            }

            MessageStatus.STOPPED -> {
                Surface(
                    shape = JarvisShapes.card,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    ) {
                        Text(
                            text = "Generation paused",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            if (canRegenerate) {
                                TextButton(onClick = onContinue) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(Spacing.md),
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text("Continue")
                                }
                            }
                            if (isLastAssistant && canRegenerate) {
                                TextButton(onClick = onRegenerate) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(Spacing.md),
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.xs))
                                    Text("Regenerate")
                                }
                            }
                        }
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

private fun routeLabelForBadge(badge: RouteBadge): String =
    when (badge.route) {
        RoutingOverride.LOCAL -> "On-device"
        RoutingOverride.AUTO -> "Auto"
        RoutingOverride.CLOUD -> "Cloud"
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

        IconButton(onClick = ::shareResponse, modifier = Modifier.size(ACTION_HIT)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share response",
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

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(ACTION_HIT),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete response",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_GLYPH),
            )
        }
    }
}
