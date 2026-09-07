package com.jarvis.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import com.jarvis.core.designsystem.JarvisBubbleShapes
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisHeaderIconButton
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import com.jarvis.core.designsystem.JarvisModePill
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisSendButton
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisSheetGrip
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.StreamingCursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val prompt: String,
)

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
                    onClose = { scope.launch { drawerState.close() } },
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
                onOpenSettings = onOpenSettings,
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
    onOpenSettings: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val lastMessageCount = uiState.messages.size
    val lastAssistantMessageId = remember(uiState.messages) {
        uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    }

    val isAtBottom by remember {
        derivedStateOf {


            if (!listState.canScrollForward) return@derivedStateOf true
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true


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

    val scope = rememberCoroutineScope()



    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    val dismissKeyboard: () -> Unit = {
        if (fieldFocused) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {




                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                        if (up != null) dismissKeyboard()
                    }
                },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

        ChatNavbar(
            title = uiState.conversationTitle,
            routingOverride = uiState.routingOverride,
            messages = uiState.messages,
            onOpenDrawer = onOpenDrawer,
            onRoutingChange = onRoutingChange,
            onOpenSettings = onOpenSettings,
            onOpenVoiceMode = onOpenVoiceMode,
        )

        if (uiState.isLoadingConversation) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                JarvisScreenLoader(label = "Loading conversation…")
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {


                if (!uiState.isSendingEnabled) {
                    OfflineBanner()
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding =
                            PaddingValues(
                                top = Spacing.sm,
                                start = Spacing.xl,
                                end = Spacing.xl,
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



                    if (!isAtBottom) {
                        Surface(
                            shape = JarvisShapes.pill,
                            color = MaterialTheme.colorScheme.onSurface,
                            shadowElevation = 4.dp,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = Spacing.md),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .clip(JarvisShapes.pill)
                                        .clickable(
                                            role = Role.Button,
                                        ) { scope.launch { listState.animateScrollToItem(lastMessageCount) } }
                                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.background,
                                    modifier = Modifier.size(Spacing.lg),
                                )
                                Text(
                                    text = "Latest",
                                    style = JarvisText.SenderLabel,
                                    color = MaterialTheme.colorScheme.background,
                                )
                            }
                        }
                    }
                }
            }
        }



        if (uiState.isPreparingSend) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                JarvisLoader(size = 16.dp)
                Text(
                    text = "Preparing…",
                    style = JarvisText.SenderLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }



        Composer(
            text = uiState.composerText,
            enabled = uiState.isSendingEnabled && !uiState.isStreaming && !uiState.isPreparingSend && !uiState.isLoadingConversation,
            isStreaming = uiState.isStreaming,
            isRecording = uiState.isRecording,
            isTranscribing = uiState.isTranscribing,
            thinkMode = uiState.thinkMode,
            onThinkModeChange = onThinkModeChange,
            onTextChange = onTextChange,
            onSend = onSend,
            onCancel = onCancel,
            onToggleRecording = onToggleRecording,
            focusRequester = focusRequester,
            onFieldFocusChange = { focused ->
                fieldFocused = focused
                if (focused) keyboard?.show()
            },
        )
        }
    }
}

private const val BEHAVIOR_BOTTOM_TOLERANCE = 128

/** The navbar — a 56dp row: hamburger, ellipsized title, route chip, overflow. */
@Composable
private fun ChatNavbar(
    title: String,
    routingOverride: RoutingOverride,
    messages: List<Message>,
    onOpenDrawer: () -> Unit,
    onRoutingChange: (RoutingOverride) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoiceMode: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JarvisHeaderIconButton(
                icon = Icons.Default.Menu,
                contentDescription = "Open history",
                onClick = onOpenDrawer,
            )

            Text(
                text = title,
                style = JarvisText.ConvTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            RouteChip(
                selected = routingOverride,
                onSelect = onRoutingChange,
            )

            Box {
                JarvisHeaderIconButton(
                    icon = Icons.Default.MoreHoriz,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                JarvisDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    JarvisDropdownItem(
                        text = "Voice mode",
                        onClick = {
                            menuOpen = false
                            onOpenVoiceMode()
                        },
                        dividerBelow = true,
                    )
                    JarvisDropdownItem(
                        text = "Share conversation",
                        onClick = {
                            menuOpen = false
                            val transcript =
                                messages.joinToString("\n\n") { msg ->
                                    val who = if (msg.role == MessageRole.USER) "You" else "Jarvis"
                                    "$who: ${msg.content}"
                                }
                            if (transcript.isNotBlank()) {
                                val intent =
                                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, transcript)
                                    }
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "Share conversation"),
                                )
                            }
                        },
                        dividerBelow = true,
                    )
                    JarvisDropdownItem(
                        text = "Settings",
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }
    }
}

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
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = "Allow ${confirmation.toolName}?",
                style = JarvisText.Body.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text =
                    "This tool can change your device or data, so Jarvis paused for your " +
                        "explicit approval. The call is recorded in the audit log.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))


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
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Sensitive-tier action. Review the parameters above — the call is recorded in the audit log.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = onDeny,
                    shape = JarvisShapes.codeBlock,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) { Text("Reject") }
                Button(onClick = onAllow, shape = JarvisShapes.codeBlock) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.lg),
                )
                Text(



                    text = "No provider connected — add one in Settings, or set up an on-device model.",
                    style = JarvisText.SenderLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                .padding(top = Spacing.xxl, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {

        Text(
            text = "Hi, I'm Jarvis.",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "What shall we dive into?",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                if (enabled) {
                    "Ask anything — I can reason, run tools, and work with your providers."
                } else {
                    "Connect a provider in Settings, or use the on-device model."
                },
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
        )
        if (enabled) {

            quickActions().chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.mdPlus),
                ) {
                    rowActions.forEach { action ->
                        QuickActionCard(
                            action = action,
                            onClick = { onSuggestion(action.prompt) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (rowActions.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.mdPlus))
            }
        }
    }
}

private fun quickActions(): List<QuickAction> =
    listOf(
        QuickAction(
            icon = Icons.Default.Code,
            label = "Explain code",
            description = "Paste a snippet, get a plain-language walkthrough.",
            prompt = "Explain this code",
        ),
        QuickAction(
            icon = Icons.Default.AutoAwesome,
            label = "Plan morning",
            description = "One priority, two small wins, then inbox.",
            prompt = "Plan my morning",
        ),
        QuickAction(
            icon = Icons.Default.EditNote,
            label = "Draft reply",
            description = "Sharp, warm, ready to send.",
            prompt = "Draft a reply",
        ),
        QuickAction(
            icon = Icons.Default.Troubleshoot,
            label = "Debug",
            description = "Find what's going wrong and why.",
            prompt = "Debug this error",
        ),
    )

@Composable
private fun QuickActionCard(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            modifier
                .clip(JarvisShapes.card)
                .clickable(onClick = onClick, role = Role.Button),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 104.dp)
                    .padding(Spacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {

            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(JarvisShapes.codeBlock)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.xlPlus),
                )
            }
            Text(
                text = action.label,
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = action.description,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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


                    softWrap = true,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
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

/** How long the copy button shows its check confirmation. */
private const val COPY_CONFIRM_MS = 1200L


private val ACTION_GLYPH = 16.dp
private val ACTION_HIT = 40.dp
private val ACTION_GAP = 4.dp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteChip(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Box {
        Text(
            text = routeLabel(selected) + " ▾",
            style = JarvisText.SenderLabel,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .clip(JarvisShapes.pill)
                    .clickable(role = Role.Button) { showSheet = true }
                    .padding(horizontal = Spacing.md, vertical = Spacing.smPlus)
                    .semantics { contentDescription = "Response route, ${routeLabel(selected)}" },
        )

        if (showSheet) {
            RouteSheet(
                selected = selected,
                onSelect = {
                    showSheet = false
                    onSelect(it)
                },
                onDismiss = { showSheet = false },
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape =
            androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = Radius.sheet,
                topEnd = Radius.sheet,
            ),
        dragHandle = {
            Box(modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)) {
                JarvisSheetGrip()
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xxl),
        ) {
            Text(
                text = "Response route",
                style = JarvisText.CodeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.xxl)
                        .padding(top = Spacing.md, bottom = Spacing.xs),
            )
            RoutingOverride.entries.forEachIndexed { index, route ->
                RouteOptionRow(
                    route = route,
                    selected = route == selected,
                    showDivider = index < RoutingOverride.entries.lastIndex,
                    onClick = { onSelect(route) },
                )
            }
        }
    }
}

/** One selectable route row inside the route sheet. */
@Composable
private fun RouteOptionRow(
    route: RoutingOverride,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val (title, description) =
        when (route) {
            RoutingOverride.AUTO -> "Auto" to "Smart routing per message"
            RoutingOverride.LOCAL -> "On-device" to "Private, works offline"
            RoutingOverride.CLOUD -> "Cloud" to "Your configured provider"
        }
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .clickable(
                        role = Role.RadioButton,
                        enabled = true,
                        onClick = onClick,
                    )
                    .padding(horizontal = Spacing.xxl, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lgPlus),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = JarvisText.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier =
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                JarvisColors.Accent.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        )
                            .semantics {
                                contentDescription = if (selected) "Selected" else "Not selected"
                            },
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
        }
    }
}

private fun routeLabel(route: RoutingOverride): String =
    when (route) {
        RoutingOverride.AUTO -> "Auto"
        RoutingOverride.LOCAL -> "On-device"
        RoutingOverride.CLOUD -> "Cloud"
    }


@Composable
private fun Composer(
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

/** A 44dp circular control inside the composer pill — the HTML .cbtn. */
@Composable
private fun ComposerCircleButton(
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
