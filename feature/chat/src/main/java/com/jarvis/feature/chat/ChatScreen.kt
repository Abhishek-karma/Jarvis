package com.jarvis.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.DEFAULT_CONVERSATION_TITLE
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.MessageStatus
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.designsystem.JarvisBadge
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisSendButton
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.designsystem.StreamingCursor
import kotlinx.coroutines.delay
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

    LaunchedEffect(Unit) {
        historyViewModel.uiEvents.collect { event ->
            when (event) {
                is HistoryUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is HistoryUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val action = pendingAction
            pendingAction = null
            if (granted) {
                action?.invoke()
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Microphone access is off — enable it in system settings to use voice.",
                    )
                }
            }
        }

    fun requireAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        snackbarHost = {
            JarvisSnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding())
        },
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
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
                    currentConversationId = uiState.conversationId,
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
                onThinkModeChange = viewModel::setThinkMode,
                onOpenVoiceMode = { requireAudioPermission { onOpenVoiceMode() } },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onToggleRecording = { requireAudioPermission { viewModel.toggleRecording() } },
                onSpeakLastResponse = viewModel::speakLastResponse,
                onSpeakMessage = viewModel::speakMessage,
                onStopSpeaking = viewModel::stopSpeaking,
                onRespondToConfirmation = viewModel::respondToConfirmation,
                onRegenerate = viewModel::regenerate,
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
    onThinkModeChange: (ThinkMode) -> Unit = {},
    onOpenVoiceMode: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onSpeakLastResponse: () -> Unit = {},
    onSpeakMessage: (String, String) -> Unit = { _, _ -> },
    onStopSpeaking: () -> Unit = {},
    onRespondToConfirmation: (Boolean) -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val lastMessageCount = uiState.messages.size
    val lastAssistantMessageId = remember(uiState.messages) {
        uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // Following counts when one of the last two items is resting at the viewport
            // bottom (within a small tolerance), so ripples/padding don't flip it.
            lastVisible.index >= info.totalItemsCount - 2 &&
                lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BEHAVIOR_BOTTOM_TOLERANCE
        }
    }

    LaunchedEffect(uiState.conversationId) {
        if (lastMessageCount > 0) {
            listState.scrollToItem(lastMessageCount)
        }
    }

    LaunchedEffect(lastMessageCount) {
        val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role == MessageRole.USER || isAtBottom) {
            listState.animateScrollToItem(lastMessageCount)
        }
    }

    LaunchedEffect(
        uiState.messages.lastOrNull()?.content?.length,
        uiState.isStreaming,
    ) {
        val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
        if (uiState.isStreaming && last.content.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(lastMessageCount)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            JarvisHeader(
                title = "",
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
                    ThinkModeSelector(
                        selected = uiState.thinkMode,
                        onSelect = onThinkModeChange,
                    )
                    IconButton(onClick = onOpenVoiceMode) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice mode")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                text = uiState.composerText,
                enabled = uiState.isSendingEnabled && !uiState.isStreaming && !uiState.isPreparingSend && !uiState.isLoadingConversation,
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
        if (uiState.isLoadingConversation) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                JarvisScreenLoader(label = "Loading conversation…")
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = Spacing.sm,
                            start = Spacing.lg,
                            end = Spacing.lg,
                            bottom = Spacing.lg,
                        ),
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
                            isPlayingAudio = uiState.playingAudioMessageId == message.id,
                            isLastAssistant = message.id == lastAssistantMessageId,
                            canRegenerate = !uiState.isStreaming && !uiState.isPreparingSend,
                            routeBadge =
                                if (message.id == lastAssistantMessageId) {
                                    uiState.routeBadge
                                } else {
                                    null
                                },
                            onSpeak = { onSpeakMessage(message.id, message.content) },
                            onStopSpeaking = onStopSpeaking,
                            onRegenerate = onRegenerate,
                        )
                    }

                    if (uiState.isAgentRunning || uiState.pendingConfirmation != null) {
                        item(key = "agent-live") {
                            AgentLiveBlock(
                                steps = uiState.agentSteps,
                                pending = uiState.pendingConfirmation,
                                onAllow = { onRespondToConfirmation(true) },
                                onDeny = { onRespondToConfirmation(false) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val BEHAVIOR_BOTTOM_TOLERANCE = 128

@Composable
private fun AgentLiveBlock(
    steps: List<AgentStep>,
    pending: AgentConfirmation?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (pending != null) {
            ConfirmationCard(confirmation = pending)
            AgentApprovalRow(pending = pending, onAllow = onAllow, onDeny = onDeny)
        } else {
            val running = steps.lastOrNull { it.state == AgentStepState.RUNNING }
            if (running != null) {
                AgentStepRow(step = running)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    JarvisLoader()
                    Text(
                        text = "Working…",
                        style = JarvisText.SenderLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationCard(confirmation: AgentConfirmation) {
    Surface(
        shape = JarvisShapes.codeBlock,
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
                text =
                    "This tool can change your device or data, so Jarvis paused for your " +
                        "explicit approval. The call is recorded in the audit log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            // Sensitive-tier approval: the full parameters must be inspectable, never
            // truncated — scroll instead of ellipsizing what the user is approving.
            Text(
                text = confirmation.argsJson,
                style = JarvisText.Code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(JarvisShapes.codeBlock)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.sm),
            )
        }
    }
}

@Composable
private fun AgentStepRow(step: AgentStep) {
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
                text = step.text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.detail != null) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
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
private fun AgentStatusIcon(state: AgentStepState) {
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
private fun AgentStatusPill(step: AgentStep) {
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
private fun AgentApprovalRow(
    pending: AgentConfirmation,
    onAllow: () -> Unit,
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
                text = "Approval required: ${pending.toolName}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Sensitive-tier action. Review the parameters above — the call is recorded in the audit log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilledTonalButton(onClick = onDeny) { Text("Reject") }
                Button(onClick = onAllow) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm, bottom = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = "Hello.",
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text =
                if (enabled) {
                    "I'm Jarvis. Ask me anything — or start with one of these."
                } else {
                    "I'm Jarvis. Connect a provider in Settings to get started."
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )
        if (enabled) {
            Column(
                modifier =
                    Modifier
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
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    // Soft warm pill on the canvas (Claude chip style — Surface Warm 1 fill, no border).
    Surface(
        shape = JarvisShapes.chip,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(JarvisShapes.chip).clickable(onClick = onClick, role = Role.Button),
    ) {
        Text(
            text = text,
            style = JarvisText.Chip,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

private fun curatedSuggestions(): List<String> {
    val sets =
        listOf(
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
    val dayIndex =
        java.time.LocalDate
            .now()
            .toEpochDay()
            .toInt()
    return sets[((dayIndex % sets.size) + sets.size) % sets.size]
}

@Composable
private fun MessageBubble(
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
                    modifier =
                        Modifier
                            .widthIn(max = maxWidth * 0.8f)
                            .clip(JarvisBubbleShapes.user)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
        return
    }

    // Agent milestone (TOOL row): a quiet single-line step.
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
                        JarvisColors.Accent.orange
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
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            JarvisMark(size = Spacing.lg)
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

        when (message.status) {
            MessageStatus.STREAMING ->
                if (message.content.isEmpty()) {
                    // Nothing to render yet — a quiet pulsing cursor signals the start.
                    StreamingCursor()
                } else {
                    // Cursor sits inline with the streaming prose (Claude spec).
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
                    // Retry the failed turn — the most likely next action after an error.
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
            // Stopped: partial prose already rendered above, nothing extra to add.
            MessageStatus.STOPPED -> Unit
            MessageStatus.COMPLETE -> {
                // Action row: quiet 20dp glyphs in 48dp hit areas, shown once the
                // response is final. Regenerate only on the latest turn.
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

        // Route badge (latest assistant turn only): what actually answered — the on-device
        // engine in solid coral, or "<model> • <provider>" in a quiet neutral pill.
        if (routeBadge != null) {
            RouteBadgeChip(routeBadge)
        }
    }
}

/** The route badge pill under the latest assistant response. */
@Composable
private fun RouteBadgeChip(badge: RouteBadge) {
    when (badge.route) {
        RoutingOverride.LOCAL ->
            JarvisBadge(
                text = badge.label,
                container = JarvisColors.Accent.orange,
                content = JarvisColors.Accent.onOrange,
            )

        RoutingOverride.AUTO, RoutingOverride.CLOUD ->
            JarvisBadge(
                text = badge.label,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/**
 * The quiet action row under a finished assistant response: Copy, Regenerate (latest
 * turn only), Share, and Read aloud. Copy swaps to a check for a beat so the tap is
 * confirmed without a snackbar.
 */
@Composable
private fun AssistantActionRow(
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

    // Native share sheet — fires an ACTION_SEND intent with the response text.
    val context = LocalContext.current
    fun shareResponse() {
        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, message.content)
            }
        context.startActivity(android.content.Intent.createChooser(intent, "Share response"))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
        modifier = Modifier.padding(top = Spacing.xs),
    ) {
        // Copy
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

        // Regenerate — only under the latest assistant turn, never while streaming.
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

        // Share
        IconButton(onClick = ::shareResponse, modifier = Modifier.size(ACTION_HIT)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share response",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_GLYPH),
            )
        }

        // Read aloud / stop
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

/** How long the copy button shows its check confirmation. */
private const val COPY_CONFIRM_MS = 1200L

/** Action-row sizing — small glyphs in compact hit areas so the row reads as
 *  metadata, not a primary toolbar. */
private val ACTION_GLYPH = 16.dp
private val ACTION_HIT = 32.dp
private val ACTION_GAP = 4.dp

/** Top-bar route selector: Auto / Local / Cloud override for the chat, opens a dropdown. */
@Composable
private fun RouteSelector(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text =
                when (selected) {
                    RoutingOverride.AUTO -> "Auto"
                    RoutingOverride.LOCAL -> "Local"
                    RoutingOverride.CLOUD -> "Cloud"
                },
            style = JarvisText.Chip,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .clip(JarvisShapes.chip)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
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

private fun routeLabel(route: RoutingOverride): String =
    when (route) {
        RoutingOverride.AUTO -> "Auto (recommended)"
        RoutingOverride.LOCAL -> "Always Local"
        RoutingOverride.CLOUD -> "Always Cloud"
    }

/**
 * Think-mode pill next to the route selector: cycles OFF → AUTO → ON. AUTO resolves per
 * message via [ThinkModeHeuristic]; ON shows the active thinking state in accent color.
 */
@Composable
private fun ThinkModeSelector(
    selected: ThinkMode,
    onSelect: (ThinkMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text =
                when (selected) {
                    ThinkMode.OFF -> "Think: Off"
                    ThinkMode.AUTO -> "Think: Auto"
                    ThinkMode.ON -> "Think: On"
                },
            style = JarvisText.Chip,
            color =
                if (selected == ThinkMode.ON) {
                    JarvisColors.Accent.orange
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier =
                Modifier
                    .clip(JarvisShapes.chip)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThinkMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(thinkModeLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun thinkModeLabel(mode: ThinkMode): String =
    when (mode) {
        ThinkMode.OFF -> "Think: Off — never reason aloud"
        ThinkMode.AUTO -> "Think: Auto — math, code & creative asks"
        ThinkMode.ON -> "Think: On — always reason"
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
    // Composer: 24dp-radius rounded container on the canvas. Bottom inset is the max of the
    // nav bar (keyboard closed) and the IME (keyboard open) — never both, so the pill sits
    // flush above the keyboard instead of gaining a static gap below it.
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(JarvisShapes.composer)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(start = Spacing.lg, top = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
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
                        val placeholder =
                            when {
                                isRecording -> "Listening…"
                                isTranscribing -> "Transcribing…"
                                else -> "Message Jarvis…"
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

                // Mic control lives inside the pill's trailing edge (hidden while
                // streaming — the circle then becomes the Stop control).
                if (!isStreaming) {
                    Box(
                        modifier =
                            Modifier
                                .padding(start = Spacing.xs)
                                .size(40.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    enabled = enabled && !isTranscribing,
                                    onClick = onToggleRecording,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isRecording ->
                                Icon(
                                    imageVector = Icons.Default.MicOff,
                                    contentDescription = "Stop recording",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(Spacing.xl),
                                )
                            isTranscribing ->
                                JarvisLoader(size = Spacing.lg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else ->
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Start recording",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Spacing.xl),
                                )
                        }
                    }
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
