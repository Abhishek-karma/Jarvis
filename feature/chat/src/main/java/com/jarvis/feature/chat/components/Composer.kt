package com.jarvis.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisModePill
import com.jarvis.core.designsystem.JarvisSendButton
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

@Composable
fun Composer(
    text: String,
    enabled: Boolean,
    isStreaming: Boolean,
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    thinkMode: ThinkMode = ThinkMode.AUTO,
    onThinkModeChange: (ThinkMode) -> Unit = {},
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    onFieldFocusChange: (Boolean) -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = Spacing.lgPlus, vertical = Spacing.smPlus),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                JarvisModePill(
                    text = thinkModeLabel(thinkMode),
                    active = thinkMode != ThinkMode.OFF,
                    icon = Icons.Default.AutoFixHigh,
                    onClick = {
                        onThinkModeChange(
                            when (thinkMode) {
                                ThinkMode.OFF -> ThinkMode.AUTO
                                ThinkMode.AUTO -> ThinkMode.ON
                                ThinkMode.ON -> ThinkMode.OFF
                            },
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(JarvisShapes.composer)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(enabled = enabled && !isRecording && !isTranscribing) {
                            focusRequester?.requestFocus()
                        }
                        .padding(start = Spacing.xs, top = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerCircleButton(
                    icon = Icons.Default.AttachFile,
                    contentDescription = "Attachments",
                    onClick = {},
                    enabled = false,
                )

                var fieldModifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp, horizontal = 2.dp)
                if (focusRequester != null) {
                    fieldModifier = fieldModifier.focusRequester(focusRequester)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = JarvisText.Body.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(JarvisColors.Accent.primary),
                    maxLines = 6,
                    enabled = !isRecording && !isTranscribing,
                    modifier = fieldModifier.onFocusChanged { onFieldFocusChange(it.isFocused) },
                    decorationBox = { innerField ->
                        val placeholder =
                            when {
                                isRecording -> "Listening…"
                                isTranscribing -> "Transcribing…"
                                else -> "Ask anything…"
                            }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            innerField()
                            if (text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = JarvisText.Body,
                                    color =
                                        if (isRecording || isTranscribing) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            }
                        }
                    },
                )

                if (!isStreaming) {
                    ComposerCircleButton(
                        icon =
                            when {
                                isRecording -> Icons.Default.MicOff
                                else -> Icons.Default.Mic
                            },
                        contentDescription =
                            when {
                                isRecording -> "Stop recording"
                                isTranscribing -> "Transcribing"
                                else -> "Voice input"
                            },
                        onClick = onToggleRecording,
                        enabled = enabled && !isTranscribing,
                        tint =
                            if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        spinning = isTranscribing,
                    )
                }

                JarvisSendButton(
                    enabled = enabled && text.isNotBlank(),
                    isStreaming = isStreaming,
                    onSend = onSend,
                    onCancel = onCancel,
                )
            }
        }
    }
}

private fun thinkModeLabel(mode: ThinkMode): String =
    when (mode) {
        ThinkMode.OFF -> "Deep thinking: Off"
        ThinkMode.AUTO -> "Deep thinking: Auto"
        ThinkMode.ON -> "Deep thinking: On"
    }

/** A 44dp circular control inside the composer pill. */
@Composable
fun ComposerCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    spinning: Boolean = false,
) {
    val resolvedTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
    val label = contentDescription
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    enabled = enabled,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) {
            JarvisLoader(size = Spacing.xlPlus, color = resolvedTint)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedTint,
                modifier = Modifier.size(Spacing.lg + 5.dp),
            )
        }
    }
}
