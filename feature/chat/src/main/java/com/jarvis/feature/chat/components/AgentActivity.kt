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
 * Compact, modern Jarvis Assistant timeline component.
 * Displays real state-machine progress, tool milestones, approval cards, and failure handling.
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
    val stateDescriptionText = "Agent status: $statusText"

    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics {
                this.contentDescription = "Jarvis Agent Activity"
                this.stateDescription = stateDescriptionText
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Header: Agent Identity, State Indicator, Subtitle & Action (Stop / Retry)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    AgentHeaderStateIcon(status = status, pending = pending)

                    Column {
                        Text(
                            text = "Jarvis Agent",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = statusText,
                            style = JarvisText.Metadata,
                            color = resolveSubtitleColor(status, pending),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (steps.isNotEmpty()) {
                        Surface(
                            shape = JarvisShapes.pill,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "${steps.size} ${if (steps.size == 1) "step" else "steps"}",
                                style = JarvisText.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                            )
                        }
                    }

                    if (status.isActive) {
                        OutlinedButton(
                            onClick = onStop,
                            shape = JarvisShapes.pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Stop Agent Execution" },
                            contentPadding = PaddingValues(
                                horizontal = Spacing.smPlus,
                                vertical = Spacing.xs,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = "Stop",
                                style = JarvisText.Caption.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    } else if (status == AgentStatus.FAILED) {
                        Button(
                            onClick = onRetry,
                            shape = JarvisShapes.pill,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Retry Agent Run" },
                            contentPadding = PaddingValues(
                                horizontal = Spacing.smPlus,
                                vertical = Spacing.xs,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = "Retry",
                                style = JarvisText.Caption.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }

            // Body 1: Pending Confirmation Approval Card (when approval is required)
            if (pending != null || status == AgentStatus.WAITING_FOR_APPROVAL) {
                pending?.let { conf ->
                    ApprovalCard(
                        confirmation = conf,
                        onAllowOnce = { onAllow(false) },
                        onAllowAlways = { onAllow(true) },
                        onDeny = onDeny,
                    )
                }
            }

            // Body 2: Failure explanation card (when status == FAILED)
            if (status == AgentStatus.FAILED) {
                FailureCard(
                    reason = failureReason ?: "An unexpected error interrupted execution.",
                    onRetry = onRetry,
                )
            }

            // Body 3: Modern Assistant Timeline Steps
            if (steps.isNotEmpty()) {
                TimelineBlock(steps = steps, isActive = status.isActive)
            }

            // Footer indicator when actively working and not waiting for approval
            if (status.isActive && pending == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        text = "Working…",
                        style = JarvisText.Caption.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Modern timeline block rendering sequential assistant steps.
 */
@Composable
private fun TimelineBlock(
    steps: List<AgentStep>,
    isActive: Boolean,
) {
    Surface(
        shape = JarvisShapes.codeBlock,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.mdPlus),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            steps.forEachIndexed { index, step ->
                TimelineStepRow(
                    step = step,
                    isLast = index == steps.lastIndex,
                )
            }
        }
    }
}

/**
 * One row in the timeline with state marker, title, duration label, redacted details, and progress.
 */
@Composable
fun TimelineStepRow(
    step: AgentStep,
    isLast: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${step.text} - ${step.state.name}"
            },
    ) {
        TimelineStepIcon(state = step.state)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = formatStepDescription(step.text),
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )

                step.durationLabel?.let { duration ->
                    Text(
                        text = duration,
                        style = JarvisText.CodeLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }

            step.detail?.let { detail ->
                val redactedDetail = remember(detail) { AuditRedaction.redact(detail) }
                Text(
                    text = redactedDetail,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (step.state == AgentStepState.RUNNING) {
                val progress = step.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(JarvisShapes.pill)
                            .padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(JarvisShapes.pill)
                            .padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}

/**
 * Step state marker: ● for running, ✓ for done, ✕ for failed, ⏹ for cancelled.
 */
@Composable
fun TimelineStepIcon(state: AgentStepState) {
    when (state) {
        AgentStepState.RUNNING -> {
            val transition = rememberInfiniteTransition(label = "step-running-pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "stepAlpha",
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        AgentStepState.DONE -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        AgentStepState.FAILED -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        AgentStepState.CANCELLED -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = "Cancelled",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * Animated or static state icon in the agent header.
 * Strictly adheres to rule: NO animation on static states (Completed, Failed, Cancelled).
 */
@Composable
private fun AgentHeaderStateIcon(
    status: AgentStatus,
    pending: AgentConfirmation?,
) {
    val isPending = pending != null || status == AgentStatus.WAITING_FOR_APPROVAL
    val isFailed = status == AgentStatus.FAILED
    val isCancelled = status == AgentStatus.CANCELLED
    val isCompleted = status == AgentStatus.COMPLETED
    val isActive = status.isActive && !isPending

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(Spacing.xxl),
    ) {
        when {
            isPending -> {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(JarvisColors.Semantic.warning.copy(alpha = 0.2f)),
                )
                Icon(
                    imageVector = Icons.Default.PriorityHigh,
                    contentDescription = "Approval required",
                    tint = JarvisColors.Semantic.warning,
                    modifier = Modifier.size(Spacing.mdPlus),
                )
            }
            isFailed -> {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(Spacing.mdPlus),
                )
            }
            isCancelled -> {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Cancelled",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.mdPlus),
                )
            }
            isCompleted -> {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Task complete",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(Spacing.mdPlus),
                )
            }
            isActive -> {
                // Subtle activity animation ONLY while active
                val infiniteTransition = rememberInfiniteTransition(label = "agent-pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseAlpha",
                )

                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.28f)),
                )
                Box(
                    modifier = Modifier
                        .size(Spacing.mdPlus)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
    }
}

/**
 * Clear Approval Card avoiding dangerous raw JSON by default.
 */
@Composable
fun ApprovalCard(
    confirmation: AgentConfirmation,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    var showRawJson by remember { mutableStateOf(false) }

    val formattedParams = remember(confirmation.argsJson) {
        parseAndFormatApprovalParams(confirmation.argsJson)
    }

    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, JarvisColors.Semantic.warning.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Approval required",
                    style = JarvisText.Body.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = JarvisShapes.pill,
                    color = JarvisColors.Semantic.warning.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = "Sensitive Tier",
                        style = JarvisText.CodeLabel,
                        color = JarvisColors.Semantic.warning,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = formatToolTitle(confirmation.toolName),
                style = JarvisText.ConvTitle,
                color = MaterialTheme.colorScheme.primary,
            )

            // Formatted parameters preview with sensitive values masked
            if (formattedParams.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(JarvisShapes.codeBlock)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(Spacing.md),
                ) {
                    formattedParams.forEach { (label, value) ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$label:",
                                style = JarvisText.Metadata.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                style = JarvisText.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { showRawJson = !showRawJson },
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(
                    text = if (showRawJson) "Hide raw JSON" else "View raw JSON",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (showRawJson) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (showRawJson) {
                Text(
                    text = confirmation.argsJson,
                    style = JarvisText.Code,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(JarvisShapes.codeBlock)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .heightIn(max = 110.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.md),
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Action Buttons with 48dp min touch targets
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    shape = JarvisShapes.pill,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Deny tool execution"
                            Role.Button
                        },
                ) {
                    Text("Deny", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onAllowOnce,
                    shape = JarvisShapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Allow tool once"
                            Role.Button
                        },
                ) {
                    Text("Allow once", fontWeight = FontWeight.SemiBold)
                }
            }

            TextButton(
                onClick = onAllowAlways,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = "Always allow tool in this chat" },
            ) {
                Text(
                    text = "Always allow for this chat",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Failure card displaying concise reason and safe retry action.
 */
@Composable
private fun FailureCard(
    reason: String,
    onRetry: () -> Unit,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.mdPlus),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Couldn't complete the task",
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = reason,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
        return "Approval required"
    }
    return when (status) {
        AgentStatus.THINKING -> "Understanding request…"
        AgentStatus.PLANNING -> "Formulating plan…"
        AgentStatus.SELECTING_TOOL -> "Selecting tool…"
        AgentStatus.WAITING_FOR_APPROVAL -> "Approval required"
        AgentStatus.RUNNING_TOOL -> "Executing action…"
        AgentStatus.WAITING_FOR_RESULT -> "Waiting for result…"
        AgentStatus.READING_RESULT -> "Reading result…"
        AgentStatus.THINKING_AGAIN -> "Thinking about result…"
        AgentStatus.SPEAKING -> "Speaking response…"
        AgentStatus.COMPLETED -> "Task complete"
        AgentStatus.FAILED -> "Couldn't complete the task"
        AgentStatus.CANCELLED -> "Execution stopped"
        AgentStatus.IDLE -> {
            when {
                steps.isEmpty() -> "Understanding request…"
                steps.any { it.state == AgentStepState.RUNNING } -> "Action in progress…"
                steps.any { it.state == AgentStepState.FAILED } -> "Couldn't complete the task"
                steps.any { it.state == AgentStepState.CANCELLED } -> "Execution stopped"
                else -> "Task complete"
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
    when (toolName) {
        "web_search", "search_web" -> "Search Web"
        "fetch_url" -> "Read Web Page"
        "send_sms", "send_message" -> "Send SMS"
        "calculator" -> "Calculator"
        "get_current_datetime" -> "Device Clock & Date"
        "launch_app" -> "App Launcher"
        "create_file" -> "Create File"
        "read_file" -> "Read File"
        "search_files" -> "Search Files"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun formatStepDescription(text: String): String =
    when {
        text.startsWith("Calling web_search") || text.startsWith("Calling search_web") -> "Search web"
        text.startsWith("Calling fetch_url") -> "Read web page"
        text.startsWith("Calling send_sms") -> "Send SMS"
        text.startsWith("Calling calculator") -> "Calculate"
        text.startsWith("Calling get_current_datetime") -> "Check system date & time"
        text.startsWith("Calling launch_app") -> "Launch application"
        text.startsWith("Calling create_file") -> "Save file"
        text.startsWith("Calling read_file") -> "Read file"
        text.startsWith("Calling search_files") -> "Search files"
        text.startsWith("Calling ") -> "Call ${text.removePrefix("Calling ").replace('_', ' ')}"
        text.endsWith(" done") -> "${text.removeSuffix(" done").replace('_', ' ').replaceFirstChar { it.uppercase() }} done"
        text.endsWith(" completed") -> "${text.removeSuffix(" completed").replace('_', ' ').replaceFirstChar { it.uppercase() }} done"
        text.endsWith(" failed") -> "${text.removeSuffix(" failed").replace('_', ' ').replaceFirstChar { it.uppercase() }} failed"
        text.startsWith("Needs your approval: ") -> "Approval required: ${text.removePrefix("Needs your approval: ").replace('_', ' ')}"
        text.startsWith("Denied ") -> "Denied: ${text.removePrefix("Denied ").replace('_', ' ')}"
        else -> text
    }
