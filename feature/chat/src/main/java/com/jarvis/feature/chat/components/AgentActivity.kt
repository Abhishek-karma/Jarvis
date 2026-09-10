package com.jarvis.feature.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.chat.AgentConfirmation
import com.jarvis.feature.chat.AgentStep
import com.jarvis.feature.chat.AgentStepState
import org.json.JSONObject

@Composable
fun AgentLiveBlock(
    steps: List<AgentStep>,
    pending: AgentConfirmation?,
    onAllow: (Boolean) -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit = {},
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Header: Agent Identity, Animated Pulse, Badge & Stop Action
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
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(Spacing.xxl),
                    ) {
                        if (pending != null) {
                            Box(
                                modifier = Modifier
                                    .size(Spacing.xxl)
                                    .clip(CircleShape)
                                    .background(JarvisColors.Semantic.warning.copy(alpha = 0.2f)),
                            )
                            Icon(
                                imageVector = Icons.Default.PriorityHigh,
                                contentDescription = null,
                                tint = JarvisColors.Semantic.warning,
                                modifier = Modifier.size(Spacing.mdPlus),
                            )
                        } else {
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
                    }

                    Column {
                        Text(
                            text = "Jarvis Agent",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                pending != null -> "Approval required"
                                steps.isEmpty() -> "Analyzing request & selecting tools…"
                                steps.any { it.state == AgentStepState.RUNNING } -> "Action in progress…"
                                else -> "Task complete"
                            },
                            style = JarvisText.Metadata,
                            color = if (pending != null) JarvisColors.Semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant,
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

                    OutlinedButton(
                        onClick = onStop,
                        shape = JarvisShapes.pill,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(
                            horizontal = Spacing.smPlus,
                            vertical = Spacing.xs,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = "Stop",
                            style = JarvisText.Caption.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }

            // Body: Pending Confirmation OR Active Step Spotlight
            if (pending != null) {
                ConfirmationCard(confirmation = pending)
                AgentApprovalRow(
                    pending = pending,
                    onAllowOnce = { onAllow(false) },
                    onAllowAlways = { onAllow(true) },
                    onDeny = onDeny,
                )
            } else {
                val running = steps.lastOrNull { it.state == AgentStepState.RUNNING } ?: steps.lastOrNull()
                if (running != null) {
                    Surface(
                        shape = JarvisShapes.codeBlock,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.mdPlus),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(Spacing.xl)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                ) {
                                    Icon(
                                        imageVector = toolIcon(running.text),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(Spacing.md),
                                    )
                                }
                                Text(
                                    text = formatStepDescription(running.text),
                                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                AgentStatusPill(step = running)
                            }

                            if (running.detail != null) {
                                Text(
                                    text = running.detail,
                                    style = JarvisText.Metadata,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = Spacing.xl + Spacing.sm),
                                )
                            }

                            if (running.state == AgentStepState.RUNNING) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(JarvisShapes.pill)
                                        .padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(vertical = Spacing.xs),
                    ) {
                        JarvisLoader(size = 18.dp)
                        Text(
                            text = "Orchestrating agent…",
                            style = JarvisText.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Collapsible Prior Steps History
                if (steps.size > 1) {
                    var historyExpanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(JarvisShapes.pill)
                                .clickable { historyExpanded = !historyExpanded }
                                .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (historyExpanded) "Hide activity history" else "Show activity history (${steps.size - 1} completed)",
                                style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = if (historyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Spacing.md),
                            )
                        }

                        if (historyExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = Spacing.xs),
                            ) {
                                steps.dropLast(1).forEach { step ->
                                    AgentStepRow(step = step)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toolIcon(nameOrText: String): ImageVector {
    val lower = nameOrText.lowercase()
    return when {
        lower.contains("search") || lower.contains("google") -> Icons.Default.Search
        lower.contains("fetch") || lower.contains("web") || lower.contains("url") -> Icons.Default.Language
        lower.contains("calc") -> Icons.Default.Calculate
        lower.contains("date") || lower.contains("time") || lower.contains("timer") || lower.contains("alarm") -> Icons.Default.Schedule
        lower.contains("file") || lower.contains("read_file") || lower.contains("write_file") -> Icons.Default.Folder
        lower.contains("launch") || lower.contains("open") -> Icons.Default.Launch
        lower.contains("battery") || lower.contains("device") || lower.contains("storage") -> Icons.Default.PhoneAndroid
        lower.contains("approval") || lower.contains("denied") -> Icons.Default.Shield
        else -> Icons.Default.AutoAwesome
    }
}

@Composable
fun ConfirmationCard(confirmation: AgentConfirmation) {
    var showRawJson by remember { mutableStateOf(false) }

    val parsedArgs = remember(confirmation.argsJson) {
        runCatching {
            val obj = JSONObject(confirmation.argsJson)
            val map = mutableListOf<Pair<String, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map.add(key to obj.opt(key).toString())
            }
            map
        }.getOrNull()
    }

    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "Allow ${formatToolTitle(confirmation.toolName)}?",
                    style = JarvisText.Body.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = JarvisShapes.pill,
                    color = JarvisColors.Semantic.warning.copy(alpha = 0.2f),
                ) {
                    Text(
                        text = "Sensitive Tier",
                        style = JarvisText.CodeLabel,
                        color = JarvisColors.Semantic.warning,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text =
                    "This tool can modify device data or execute actions externally. " +
                        "Review the requested parameters before granting approval.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))

            if (!parsedArgs.isNullOrEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(JarvisShapes.codeBlock)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(Spacing.md),
                ) {
                    parsedArgs.forEach { (key, value) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "$key:",
                                style = JarvisText.Code.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = value,
                                style = JarvisText.Code,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { showRawJson = !showRawJson },
                modifier = Modifier.padding(top = Spacing.xs),
            ) {
                Text(
                    text = if (showRawJson) "Hide raw JSON" else "View raw JSON",
                    style = JarvisText.Metadata,
                )
            }

            if (showRawJson || parsedArgs.isNullOrEmpty()) {
                Text(
                    text = confirmation.argsJson,
                    style = JarvisText.Code,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(JarvisShapes.codeBlock)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .heightIn(max = 110.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.mdPlus),
                )
            }
        }
    }
}

private fun formatToolTitle(toolName: String): String =
    when (toolName) {
        "web_search", "search_web" -> "Web Search"
        "fetch_url" -> "Web Page Fetch"
        "calculator" -> "Calculator"
        "get_current_datetime" -> "Device Clock & Date"
        "launch_app" -> "App Launcher"
        "create_file" -> "Create File"
        "read_file" -> "Read File"
        "search_files" -> "File Search"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun formatStepDescription(text: String): String =
    when {
        text.startsWith("Calling web_search") || text.startsWith("Calling search_web") -> "Searching the web"
        text.startsWith("Calling fetch_url") -> "Retrieving webpage content"
        text.startsWith("Calling calculator") -> "Evaluating calculation"
        text.startsWith("Calling get_current_datetime") -> "Checking system date & time"
        text.startsWith("Calling launch_app") -> "Launching application"
        text.startsWith("Calling create_file") -> "Saving file"
        text.startsWith("Calling read_file") -> "Reading local file"
        text.startsWith("Calling search_files") -> "Searching device files"
        text.startsWith("Calling ") -> "Executing ${text.removePrefix("Calling ").replace('_', ' ')}"
        text.endsWith(" done") -> "${text.removeSuffix(" done").replace('_', ' ').replaceFirstChar { it.uppercase() }} complete"
        text.endsWith(" failed") -> "${text.removeSuffix(" failed").replace('_', ' ').replaceFirstChar { it.uppercase() }} failed"
        text.startsWith("Needs your approval: ") -> "Approval required: ${text.removePrefix("Needs your approval: ").replace('_', ' ')}"
        text.startsWith("Denied ") -> "Denied: ${text.removePrefix("Denied ").replace('_', ' ')}"
        else -> text
    }

@Composable
fun AgentStepRow(step: AgentStep) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AgentStatusIcon(state = step.state)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = formatStepDescription(step.text),
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.detail != null) {
                Text(
                    text = step.detail,
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(Spacing.xs)
                                .clip(JarvisShapes.pill),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(Spacing.xs)
                                .clip(JarvisShapes.pill),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
        AgentStatusPill(step = step)
    }
}

@Composable
fun AgentStatusIcon(state: AgentStepState) {
    when (state) {
        AgentStepState.DONE ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(Spacing.lg),
                )
            }
        AgentStepState.RUNNING -> JarvisLoader(size = Spacing.xxl)
        AgentStepState.FAILED ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(Spacing.xxl)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(Spacing.lg),
                )
            }
    }
}

@Composable
fun AgentStatusPill(step: AgentStep) {
    val running = step.state == AgentStepState.RUNNING
    val failed = step.state == AgentStepState.FAILED
    val containerColor =
        when {
            running -> MaterialTheme.colorScheme.primaryContainer
            failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        when {
            running -> MaterialTheme.colorScheme.onPrimaryContainer
            failed -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val label =
        when {
            running -> "Running"
            failed -> "Failed"
            step.durationLabel != null -> step.durationLabel
            else -> "Done"
        }
    Surface(
        shape = JarvisShapes.pill,
        color = containerColor,
    ) {
        Text(
            text = label,
            style =
                if (!running && !failed && step.durationLabel != null) {
                    JarvisText.CodeLabel
                } else {
                    JarvisText.Caption
                },
            color = contentColor,
            maxLines = 1,
            modifier =
                Modifier.padding(
                    horizontal = Spacing.sm,
                    vertical = Spacing.xs,
                ),
        )
    }
}

@Composable
fun AgentApprovalRow(
    pending: AgentConfirmation,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(Spacing.xxl)
                    .clip(CircleShape)
                    .background(JarvisColors.Semantic.warning),
        ) {
            Icon(
                imageVector = Icons.Default.PriorityHigh,
                contentDescription = null,
                tint = JarvisColors.Dark.canvas,
                modifier = Modifier.size(Spacing.lg),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "Approval required: ${formatToolTitle(pending.toolName)}",
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Sensitive-tier action. Choose whether to permit this single operation or trust it for this conversation.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    shape = JarvisShapes.codeBlock,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onAllowOnce,
                    shape = JarvisShapes.codeBlock,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text("Allow once")
                }
                Button(
                    onClick = onAllowAlways,
                    shape = JarvisShapes.codeBlock,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Always")
                }
            }
        }
    }
}
