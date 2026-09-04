package com.jarvis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisSendButton
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.designsystem.StreamingCursor
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(
    onOpenSettings: () -> Unit = {},
    onOpenVoiceMode: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface one-shot events (errors, routing notices) as snackbars.
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is ChatUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is ChatUiEvent.ShowNotice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = Modifier.padding(innerPadding),
            drawerContent = {
                HistoryDrawerContent(
                    onOpenConversation = { conversationId ->
                        scope.launch { drawerState.close() }
                        viewModel.openConversationById(conversationId)
                    },
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        viewModel.createNewConversation()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    viewModel = historyViewModel,
                )
            },
        ) {
            ChatScreen(
                uiState = uiState,
                onTextChange = viewModel::onTextChange,
                onSend = viewModel::sendMessage,
                onCancel = viewModel::cancelStreaming,
                onRoutingChange = viewModel::setRoutingOverride,
                onOpenSettings = onOpenSettings,
                onOpenVoiceMode = onOpenVoiceMode,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onToggleRecording = viewModel::toggleRecording,
                onSpeakLastResponse = viewModel::speakLastResponse,
                onStopSpeaking = viewModel::stopSpeaking,
                onRespondToConfirmation = viewModel::respondToConfirmation,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit = {},
    onRoutingChange: (RoutingOverride) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenVoiceMode: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onSpeakLastResponse: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onRespondToConfirmation: (Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val lastMessageCount = uiState.messages.size

    // Auto-scroll to the newest message as it arrives.
    LaunchedEffect(lastMessageCount, uiState.isStreaming) {
        if (lastMessageCount > 0) {
            listState.animateScrollToItem(lastMessageCount - 1)
        }
    }

    // ── Agent Canvas (04-DESIGN.md Screen 5) ────────────────────────────────────────
    var agentSheetOpen by remember { mutableStateOf(false) }
    val pending = uiState.pendingConfirmation
    val canvasVisible = uiState.isAgentRunning || pending != null

    // A new run or a parked Sensitive-tier call auto-opens the sheet; Hide collapses it
    // to a pill while the run keeps going.
    LaunchedEffect(canvasVisible, pending) {
        if (canvasVisible) agentSheetOpen = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.conversationTitle) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "History")
                    }
                },
                actions = {
                    RouteSelector(
                        selected = uiState.routingOverride,
                        onSelect = onRoutingChange,
                    )
                    IconButton(onClick = onOpenVoiceMode) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice mode")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                text = uiState.composerText,
                enabled = uiState.isSendingEnabled && !uiState.isStreaming,
                isStreaming = uiState.isStreaming,
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                onTextChange = onTextChange,
                onSend = onSend,
                onCancel = onCancel,
                onToggleRecording = onToggleRecording,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (uiState.messages.isEmpty()) {
                item(key = "empty-state") {
                    EmptyChatState(
                        enabled = uiState.isSendingEnabled,
                        onSuggestion = { suggestion -> onTextChange(suggestion) },
                    )
                }
            }
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isPlayingAudio = uiState.isPlayingAudio,
                    onSpeak = onSpeakLastResponse,
                    onStopSpeaking = onStopSpeaking,
                )
            }
        }
    }

    // Agent Canvas overlay (Screen 5): the step sheet, or the collapsed pill while hidden.
    if (uiState.isAgentRunning && !agentSheetOpen && uiState.agentSteps.isNotEmpty()) {
        AgentProgressPill(onClick = { agentSheetOpen = true })
    }
    if (agentSheetOpen && canvasVisible) {
        ModalBottomSheet(onDismissRequest = { agentSheetOpen = false }) {
            AgentCanvasContent(
                steps = uiState.agentSteps,
                pending = pending,
                onAllow = { onRespondToConfirmation(true) },
                onDeny = { onRespondToConfirmation(false) },
                onHide = { agentSheetOpen = false },
                onStop = onCancel,
            )
        }
    }
}

/**
 * Collapsed agent pill shown while the canvas is hidden but the run continues (Screen 5).
 * Wrapped in a transparent full-size Box so the pill floats above the composer without
 * blocking scroll or taps elsewhere (only the pill itself is clickable).
 */
@Composable
private fun AgentProgressPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = JarvisShapes.chip,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(bottom = 96.dp)
                .clip(JarvisShapes.chip)
                .clickable(onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Agent working",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Agent Canvas bottom sheet per 04-DESIGN.md Screen 5: step list with checkmarks, the
 * running row highlighted inline, and a non-blocking progress feel. When a Sensitive-tier
 * tool parks the run, the sheet becomes the confirmation sheet (06-AGENT.md §4) and shows
 * the pending action's full parameters with Allow/Deny.
 */
@Composable
private fun AgentCanvasContent(
    steps: List<AgentStep>,
    pending: AgentConfirmation?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onHide: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.lg),
    ) {
        Text(
            text = if (pending != null) "Agent needs your input" else "Agent working",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (pending != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            ConfirmationCard(confirmation = pending)
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        HorizontalDivider()

        if (steps.isEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text("Starting…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                steps.forEach { step -> AgentStepRow(step = step) }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onHide) { Text("Hide") }
            if (pending != null) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(onClick = onAllow) { Text("Allow") }
            } else {
                Spacer(modifier = Modifier.width(Spacing.sm))
                OutlinedButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Stop")
                }
            }
        }
    }
}

/** The parked Sensitive-tier call: what will run, with its full parameters (06-AGENT.md §4). */
@Composable
private fun ConfirmationCard(confirmation: AgentConfirmation) {
    Surface(
        shape = JarvisShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = "Allow ${confirmation.toolName}?",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "This tool can change your device or data, so Jarvis paused for your " +
                    "explicit approval. The call is recorded in the audit log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = confirmation.argsJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(JarvisShapes.medium)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(Spacing.sm),
            )
        }
    }
}

/** One row in the canvas step list: ✓ for completed work, inline spinner on the current row. */
@Composable
private fun AgentStepRow(step: AgentStep) {
    val running = step.state == AgentStepState.RUNNING
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Done",
                tint = JarvisColors.Accent.orange,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = step.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (running) {
                JarvisColors.Accent.orange
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (running) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Empty state per 04-DESIGN.md Screen 1: welcome display text plus 4 suggestion chips.
 * The chip set rotates from a curated list by day (deterministic across launches within
 * a day, not random every open).
 */
@Composable
private fun EmptyChatState(
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl, bottom = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = "Hello.",
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text = if (enabled) {
                "I'm Jarvis. Ask me anything — or start with one of these."
            } else {
                "I'm Jarvis. Connect a provider in Settings to get started."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                curatedSuggestions().forEach { suggestion ->
                    SuggestionChip(text = suggestion, onClick = { onSuggestion(suggestion) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    // Soft warm pill on the canvas (Claude chip style — Surface Warm 1 fill, no border).
    Surface(
        shape = JarvisShapes.chip,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(JarvisShapes.chip).clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = JarvisText.Chip,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

/** Curated suggestion sets — one set shown per day, rotating deterministically. */
private fun curatedSuggestions(): List<String> {
    val sets = listOf(
        listOf(
            "Explain quantum computing like I'm five",
            "Draft a polite email declining a meeting",
            "Give me 5 ideas for a weekend project",
            "Summarize the pros and cons of Kotlin vs Java",
        ),
        listOf(
            "Write a haiku about debugging at 2am",
            "Help me plan a healthy week of meals",
            "Explain how HTTPS actually works",
            "Brainstorm names for a coffee shop",
        ),
        listOf(
            "What should I ask my landlord before renewing?",
            "Turn these notes into a to-do list",
            "Explain this error: null pointer exception",
            "Give me a 20-minute home workout",
        ),
    )
    val dayIndex = java.time.LocalDate.now().toEpochDay().toInt()
    return sets[((dayIndex % sets.size) + sets.size) % sets.size]
}

/**
 * Message rendering per the fused spec: user messages are a right-aligned soft-gray
 * pill with asymmetric corners (18/18/18/4 — ChatGPT bubble); assistant messages are
 * bubble-less inline prose led by the orange asterisk logomark (Claude signature),
 * with the blinking orange cursor while streaming and a quiet feedback row below.
 */
@Composable
private fun MessageBubble(
    message: Message,
    isPlayingAudio: Boolean = false,
    onSpeak: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
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
                    modifier = Modifier
                        .widthIn(max = maxWidth * 0.8f)
                        .clip(JarvisBubbleShapes.user)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Spacing.lg, vertical = 10.dp),
                )
            }
        }
        return
    }

    // Assistant: no bubble — flowing prose on the canvas.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            JarvisMark(size = 16.dp)
            Text(
                text = "Jarvis",
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            Text(
                text = reasoning,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (message.content.isNotEmpty()) {
            MarkdownText(markdown = message.content)
        }

        when (message.status.name) {
            "STREAMING" -> if (message.content.isEmpty()) {
                // Nothing to render yet — a quiet pulsing cursor signals the start.
                StreamingCursor()
            } else {
                // Cursor sits inline with the streaming prose (Claude spec).
                StreamingCursor(Modifier.padding(top = 2.dp))
            }
            "ERROR" -> Text(
                text = message.errorHint ?: "Failed",
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.error,
            )
            "COMPLETE" -> {
                // Feedback row: quiet 20dp glyphs in 48dp hit areas (ChatGPT spec §8).
                if (message.content.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (isPlayingAudio) onStopSpeaking() else onSpeak() }) {
                            Icon(
                                imageVector = if (isPlayingAudio) Icons.AutoMirrored.Filled.VolumeOff
                                else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isPlayingAudio) "Stop speaking" else "Read aloud",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top-bar route selector (Feature 6 §Settings): Auto / Always Local / Always Cloud,
 * per chat. Shows a ⚡ badge with the current override label and opens a dropdown.
 */
@Composable
private fun RouteSelector(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = when (selected) {
                RoutingOverride.AUTO -> "Auto"
                RoutingOverride.LOCAL -> "Local"
                RoutingOverride.CLOUD -> "Cloud"
            },
            style = JarvisText.Chip,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(JarvisShapes.chip)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = Spacing.lg, vertical = 6.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RoutingOverride.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(routeLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun routeLabel(route: RoutingOverride): String = when (route) {
    RoutingOverride.AUTO -> "Auto (recommended)"
    RoutingOverride.LOCAL -> "Always Local"
    RoutingOverride.CLOUD -> "Always Cloud"
}

@Composable
private fun Composer(
    text: String,
    enabled: Boolean,
    isStreaming: Boolean,
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onToggleRecording: () -> Unit = {},
) {
    // ChatGPT composer: 24dp-radius rounded container on the canvas; Claude's paper
    // fill with a 1dp hairline border. Send circle only exists when there is text.
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(JarvisShapes.composer)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, JarvisShapes.composer)
                    .padding(horizontal = Spacing.lg, vertical = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = JarvisText.Body.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(JarvisColors.Accent.orange),
                    maxLines = 5,
                    enabled = !isRecording && !isTranscribing,
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerField ->
                        Column(modifier = Modifier.padding(vertical = Spacing.md)) {
                            if (text.isEmpty()) {
                                Text(
                                    text = when {
                                        isRecording -> "Listening…"
                                        isTranscribing -> "Transcribing…"
                                        else -> "Message Jarvis…"
                                    },
                                    style = JarvisText.Body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerField()
                        }
                    },
                )

                // Trailing voice affordance inside the pill — swaps to the send
                // circle outside the pill once there is text (both source specs).
                if (!isStreaming) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = Spacing.md)
                            .size(28.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled && !isTranscribing,
                                onClick = onToggleRecording,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isRecording -> Icon(
                                imageVector = Icons.Default.MicOff,
                                contentDescription = "Stop recording",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            isTranscribing -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            else -> Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Start recording",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            JarvisSendButton(
                enabled = enabled && text.isNotBlank(),
                isStreaming = isStreaming,
                onSend = onSend,
                onCancel = onCancel,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
    }
}
