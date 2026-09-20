package com.jarvis.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.agent.AuditRedaction
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.chat.AgentConfirmation
import com.jarvis.feature.chat.AgentStatus
import com.jarvis.feature.chat.AgentStep
import com.jarvis.feature.chat.AgentStepState
import org.json.JSONObject

/**
 * Compact inline status for agent activity - ChatGPT-style.
 * Shows simple status text without detailed tool logs.
 */
@Composable
fun AgentLiveBlock(
    steps: List<AgentStep>,
    pending: AgentConfirmation?,
    status: AgentStatus = AgentStatus.IDLE,
    failureReason: String? = null,
    onAllow: (Boolean) -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val statusText = resolveStatusSubtitle(status, pending, steps, failureReason)
    val isActive = status.isActive || pending != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Compact status row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Simple pulsing dot for active states
            if (isActive) {
                PulsingDot()
            } else if (status == AgentStatus.COMPLETED) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            } else if (status == AgentStatus.FAILED) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }

            Text(
                text = statusText,
                style = JarvisText.Metadata,
                color = resolveSubtitleColor(status, pending),
                modifier = Modifier.weight(1f),
            )

            // Compact retry button only on failure
            if (status == AgentStatus.FAILED) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 2.dp),
                ) {
                    Text(
                        text = "Retry",
                        style = JarvisText.Caption,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Compact approval card (when needed)
        if (pending != null || status == AgentStatus.WAITING_FOR_APPROVAL) {
            pending?.let { conf ->
                CompactApprovalCard(
                    confirmation = conf,
                    onAllowOnce = { onAllow(false) },
                    onAllowAlways = { onAllow(true) },
                    onDeny = onDeny,
                )
            }
        }

        // Simple failure message (no card)
        if (status == AgentStatus.FAILED && !failureReason.isNullOrEmpty()) {
            Text(
                text = "Some actions couldn't complete.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

// Timeline, FailureCard and AgentHeaderStateIcon removed for simplicity.
// Use AgentStatusLine for compact status display instead.

/**
 * Compact approval card for permission requests.
 */
@Composable
fun CompactApprovalCard(
    confirmation: AgentConfirmation,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    val formattedParams = remember(confirmation.argsJson) {
        parseAndFormatApprovalParams(confirmation.argsJson)
    }

    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, JarvisColors.Semantic.warning.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.PriorityHigh,
                    contentDescription = null,
                    tint = JarvisColors.Semantic.warning,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Permission required",
                    style = JarvisText.SenderLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatToolTitle(confirmation.toolName),
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Compact params preview
            if (formattedParams.isNotEmpty()) {
                Text(
                    text = formattedParams.joinToString(" · ") { "${it.first}: ${it.second}" },
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Compact action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    shape = JarvisShapes.pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    Text("Deny", style = JarvisText.Caption)
                }

                Button(
                    onClick = onAllowOnce,
                    shape = JarvisShapes.pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    Text("Allow", style = JarvisText.Caption)
                }
            }
        }
    }
}

// FailureCard removed - failures are now shown as inline text in AgentLiveBlock

/**
 * Parses tool arguments and presents masked, user-friendly labels.
 */
private fun parseAndFormatApprovalParams(argsJson: String): List<Pair<String, String>> {
    return runCatching {
        val obj = JSONObject(argsJson)
        val list = mutableListOf<Pair<String, String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val rawVal = obj.opt(rawKey)?.toString().orEmpty()
            val label = when (rawKey.lowercase()) {
                "to", "recipient", "phone", "number" -> "To"
                "message", "body", "content", "text" -> "Message"
                "subject", "title" -> "Subject"
                "command", "cmd" -> "Command"
                "path", "file", "filepath" -> "Path"
                "query", "q" -> "Query"
                "url", "uri" -> "URL"
                else -> rawKey.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            val formattedValue = when {
                rawKey.lowercase() in listOf("to", "recipient", "phone", "number") -> maskPhoneNumber(rawVal)
                rawKey.lowercase() in listOf("password", "token", "secret", "apikey", "api_key", "key") -> "••••••••"
                rawKey.lowercase() in listOf("message", "body", "content", "text") -> "\"$rawVal\""
                else -> rawVal
            }
            list.add(label to formattedValue)
        }
        list
    }.getOrElse { emptyList() }
}

private fun maskPhoneNumber(phone: String): String {
    val clean = phone.trim()
    return if (clean.length > 6) {
        val prefix = clean.take(3)
        val suffix = clean.takeLast(4)
        "$prefix••••••$suffix"
    } else {
        clean
    }
}

private fun resolveStatusSubtitle(
    status: AgentStatus,
    pending: AgentConfirmation?,
    steps: List<AgentStep>,
    failureReason: String?,
): String {
    if (pending != null || status == AgentStatus.WAITING_FOR_APPROVAL) {
        return "Permission required"
    }
    return when (status) {
        AgentStatus.THINKING -> "Thinking…"
        AgentStatus.PLANNING -> "Planning…"
        AgentStatus.SELECTING_TOOL -> "Using tools…"
        AgentStatus.WAITING_FOR_APPROVAL -> "Permission required"
        AgentStatus.RUNNING_TOOL -> "Working…"
        AgentStatus.WAITING_FOR_RESULT -> "Waiting…"
        AgentStatus.READING_RESULT -> "Reading…"
        AgentStatus.THINKING_AGAIN -> "Reasoning…"
        AgentStatus.SPEAKING -> "Speaking…"
        AgentStatus.COMPLETED -> "Done"
        AgentStatus.FAILED -> "Failed"
        AgentStatus.CANCELLED -> "Stopped"
        AgentStatus.IDLE -> {
            when {
                steps.isEmpty() -> "Thinking…"
                steps.any { it.state == AgentStepState.RUNNING } -> "Working…"
                steps.any { it.state == AgentStepState.FAILED } -> "Failed"
                steps.any { it.state == AgentStepState.CANCELLED } -> "Stopped"
                else -> "Done"
            }
        }
    }
}

@Composable
private fun resolveSubtitleColor(status: AgentStatus, pending: AgentConfirmation?): Color {
    return when {
        pending != null || status == AgentStatus.WAITING_FOR_APPROVAL -> JarvisColors.Semantic.warning
        status == AgentStatus.FAILED -> MaterialTheme.colorScheme.error
        status == AgentStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        status == AgentStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatToolTitle(toolName: String): String =
    AssistantActionFormatter.toHumanReadableTitle(toolName)

private fun formatStepDescription(text: String): String =
    when {
        text.startsWith("Approval required: ") -> text
        text.startsWith("Denied ") -> text
        text.startsWith("Calling ") -> {
            val rawTool = text.removePrefix("Calling ")
            AssistantActionFormatter.toProgressDescription(rawTool)
        }
        text.endsWith(" done") -> {
            val rawTool = text.removeSuffix(" done")
            AssistantActionFormatter.toCompletedDescription(rawTool)
        }
        text.endsWith(" completed") -> {
            val rawTool = text.removeSuffix(" completed")
            AssistantActionFormatter.toCompletedDescription(rawTool)
        }
        text.endsWith(" failed") -> {
            val rawTool = text.removeSuffix(" failed")
            "${AssistantActionFormatter.toCompletedDescription(rawTool)} (failed)"
        }
        else -> text
    }
