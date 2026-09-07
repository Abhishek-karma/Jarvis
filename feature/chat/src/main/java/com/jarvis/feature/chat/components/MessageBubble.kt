package com.jarvis.feature.chat.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisMark
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
) {
    val isUser = message.role == MessageRole.USER

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            BoxWithConstraints {
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
                            .padding(horizontal = Spacing.lg, vertical = Spacing.mdPlus),
                )
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(bottom = Spacing.xs),
            ) {
                Text(
                    text = "Reasoned",
                    style = JarvisText.SenderLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = reasoning,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (message.content.isNotEmpty()) {
            MarkdownText(markdown = message.content)
        }

        when (message.status) {
            MessageStatus.STREAMING ->
                if (message.content.isEmpty()) {
                    StreamingCursor()
                } else {
                    StreamingCursor(Modifier.padding(top = Spacing.xs))
                }
            MessageStatus.ERROR -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Default.PriorityHigh,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = message.errorHint ?: "Stream failed",
                        style = JarvisText.SenderLabel,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )

                    if (isLastAssistant && canRegenerate) {
                        IconButton(onClick = onRegenerate) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry response",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(Spacing.xl),
                            )
                        }
                    }
                }
            }

            MessageStatus.STOPPED -> Unit
            MessageStatus.COMPLETE -> {
                if (message.content.isNotEmpty()) {
                    AssistantActionRow(
                        message = message,
                        isPlayingAudio = isPlayingAudio,
                        showRegenerate = isLastAssistant && canRegenerate,
                        onSpeak = onSpeak,
                        onStopSpeaking = onStopSpeaking,
                        onRegenerate = onRegenerate,
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
    }
}
