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
    userFacingState: com.jarvis.core.agent.execution.UserFacingState? = null,
    onAllow: (Boolean) -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
    onActionIntent: (String) -> Unit = {},
) {
    val statusText = when {
        status == AgentStatus.FAILED && userFacingState != null -> userFacingState.message
        status == AgentStatus.CANCELLED -> "Stopped"
        else -> resolveStatusSubtitle(status, pending, steps, failureReason)
    }
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

            // Actionable button on failure or suggested action
            if (status == AgentStatus.FAILED) {
                val actionLabel = userFacingState?.suggestedAction ?: "Retry"
                val actionIntent = userFacingState?.actionIntent
                TextButton(
                    onClick = {
                        if (actionIntent != null && actionIntent != "retry") {
                            onActionIntent(actionIntent)
                        } else {
                            onRetry()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 2.dp),
                ) {
                    Text(
                        text = actionLabel,
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
    val actionPrompt = remember(confirmation.toolName, confirmation.argsJson) {
        formatActionPrompt(confirmation.toolName, confirmation.argsJson)
    } ?: formatToolTitle(confirmation.toolName)

    val (allowLabel, denyLabel) = remember(confirmation.toolName, confirmation.argsJson) {
        val isSend = confirmation.toolName == "send_sms" ||
            confirmation.toolName == "send_app_message" ||
            (confirmation.toolName == "ui_click" && confirmation.argsJson.contains("send", ignoreCase = true))
        if (isSend) {
            "Send" to "Cancel"
        } else when (confirmation.toolName) {
            "place_call" -> "Call" to "Cancel"
            else -> "Allow" to "Deny"
        }
    }

    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = actionPrompt,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Compact params preview
            if (formattedParams.isNotEmpty()) {
                val detailText = formattedParams.joinToString(" · ") { "${it.first}: ${it.second}" }
                Text(
                    text = detailText,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(Spacing.xs))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    shape = JarvisShapes.pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    Text(denyLabel, style = JarvisText.Caption)
                }

                Button(
                    onClick = onAllowOnce,
                    shape = JarvisShapes.pill,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    Text(allowLabel, style = JarvisText.Caption)
                }
            }
        }
    }
}

// FailureCard removed - failures are now shown as inline text in AgentLiveBlock

/**
 * Parses tool arguments and presents masked, user-friendly labels.
 */
private val APPROVAL_CONTACT_KEYS = setOf("to", "recipient", "phone", "number")
private val APPROVAL_MESSAGE_KEYS = setOf("message", "body", "content", "text")
private val APPROVAL_SECRET_KEYS = setOf("password", "token", "secret", "apikey", "api_key", "key")
private val APPROVAL_LABELS = mapOf(
    "to" to "To", "recipient" to "To", "phone" to "To", "number" to "To",
    "message" to "Message", "body" to "Message", "content" to "Message", "text" to "Message",
    "subject" to "Subject", "title" to "Subject",
    "command" to "Command", "cmd" to "Command",
    "path" to "Path", "file" to "Path", "filepath" to "Path",
    "query" to "Query", "q" to "Query",
    "url" to "URL", "uri" to "URL",
)

private fun formatActionPrompt(toolName: String, argsJson: String): String? =
    runCatching {
        val obj = JSONObject(argsJson)
        when (toolName) {
            "place_call" -> {
                val number = obj.optString("number").ifBlank { obj.optString("phone").ifBlank { obj.optString("to") } }
                val name = obj.optString("name").ifBlank { obj.optString("recipient") }
                val masked = if (number.isNotBlank()) maskPhoneNumber(number) else ""
                when {
                    name.isNotBlank() && masked.isNotBlank() -> "Call $name at $masked?"
                    masked.isNotBlank() -> "Call $masked?"
                    name.isNotBlank() -> "Call $name?"
                    else -> "Place phone call?"
                }
            }
            "send_sms" -> {
                val to = obj.optString("to").ifBlank { obj.optString("number").ifBlank { obj.optString("phone") } }
                val name = obj.optString("name").ifBlank { obj.optString("recipient") }
                val masked = if (to.isNotBlank()) maskPhoneNumber(to) else ""
                when {
                    name.isNotBlank() && masked.isNotBlank() -> "Send SMS to $name at $masked?"
                    masked.isNotBlank() -> "Send SMS to $masked?"
                    name.isNotBlank() -> "Send SMS to $name?"
                    else -> "Send text message?"
                }
            }
            "send_app_message" -> {
                val to = obj.optString("recipient").ifBlank { obj.optString("to") }
                val app = obj.optString("app_name")
                if (to.isNotBlank() && app.isNotBlank()) "Send message to $to on $app?"
                else if (to.isNotBlank()) "Send message to $to?"
                else "Send message?"
            }
            "ui_click" -> {
                val target = obj.optString("target")
                val tLower = target.lowercase()
                when {
                    tLower.contains("send") -> "Send message?"
                    tLower.contains("delete") || tLower.contains("remove") -> "Delete item?"
                    tLower.contains("pay") || tLower.contains("buy") || tLower.contains("order") -> "Confirm purchase?"
                    tLower.contains("post") || tLower.contains("publish") -> "Publish post?"
                    else -> "Confirm action: $target?"
                }
            }
            else -> null
        }
    }.getOrNull()

private fun parseAndFormatApprovalParams(argsJson: String): List<Pair<String, String>> =
    runCatching {
        JSONObject(argsJson).let { obj ->
            val keys = obj.keys()
            buildList {
                while (keys.hasNext()) {
                    val rawKey = keys.next()
                    val key = rawKey.lowercase()
                    val rawVal = obj.opt(rawKey)?.toString().orEmpty()
                    val label = APPROVAL_LABELS[key]
                        ?: rawKey.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    val value = when {
                        key in APPROVAL_CONTACT_KEYS -> maskPhoneNumber(rawVal)
                        key in APPROVAL_SECRET_KEYS -> "••••••••"
                        key in APPROVAL_MESSAGE_KEYS -> "\"$rawVal\""
                        else -> rawVal
                    }
                    add(label to value)
                }
            }
        }
    }.getOrElse { emptyList() }

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
            AssistantActionFormatter.toCompletedDescription(rawTool) ?: "${rawTool.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }} done"
        }
        text.endsWith(" completed") -> {
            val rawTool = text.removeSuffix(" completed")
            AssistantActionFormatter.toCompletedDescription(rawTool) ?: "${rawTool.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }} completed"
        }
        text.endsWith(" failed") -> {
            val rawTool = text.removeSuffix(" failed")
            val desc = AssistantActionFormatter.toCompletedDescription(rawTool) ?: rawTool.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
            "$desc (failed)"
        }
        else -> text
    }
