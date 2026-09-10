package com.jarvis.feature.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisScreenLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.chat.components.AgentLiveBlock
import com.jarvis.feature.chat.components.ChatNavbar
import com.jarvis.feature.chat.components.Composer
import com.jarvis.feature.chat.components.EmptyChatState
import com.jarvis.feature.chat.components.MessageBubble
import com.jarvis.feature.chat.components.OfflineBanner
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val BEHAVIOR_BOTTOM_TOLERANCE = 128

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
    onRespondToConfirmation: (Boolean, Boolean) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onRetryMessage: (String) -> Unit = {},
    onEditMessage: (Message) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onContinueGenerating: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPermissions: () -> Unit = onOpenSettings,
) {
    val listState = rememberLazyListState()
    val lastMessageCount = uiState.messages.size
    val lastAssistantMessageId = remember(uiState.messages) {
        uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    }

    var followTail by remember { mutableStateOf(true) }
    val programmaticScroll = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var tailScrollJob by remember { mutableStateOf<Job?>(null) }
    val scrollToTail: (Boolean) -> Unit = { animate ->
        tailScrollJob?.cancel()
        tailScrollJob =
            scope.launch {
                programmaticScroll.value = true
                try {
                    if (animate) {
                        listState.animateScrollToItem(lastMessageCount)
                    } else {
                        listState.scrollToItem(lastMessageCount)
                    }
                } finally {
                    programmaticScroll.value = false
                }
            }
    }

    val isAtBottom by remember {
        derivedStateOf {
            if (!listState.canScrollForward) return@derivedStateOf true
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val offset = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
            lastVisible.index >= info.totalItemsCount - 2 &&
                abs(offset) <= BEHAVIOR_BOTTOM_TOLERANCE
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to programmaticScroll.value }
            .collect { (scrolling, programmatic) ->
                if (scrolling && !programmatic) {
                    followTail = false
                } else if (!scrolling) {
                    followTail = isAtBottom
                }
            }
    }

    LaunchedEffect(uiState.conversationId) {
        followTail = true
        if (lastMessageCount > 0) {
            scrollToTail(false)
        }
    }

    LaunchedEffect(lastMessageCount) {
        val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role == MessageRole.USER) {
            followTail = true
            scrollToTail(true)
        } else if (followTail) {
            scrollToTail(true)
        }
    }

    LaunchedEffect(
        uiState.messages.lastOrNull()?.content?.length,
        uiState.isStreaming,
    ) {
        val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
        if (uiState.isStreaming && last.content.isNotEmpty() && followTail) {
            scrollToTail(true)
        }
    }

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
                                    onRetry = onRetryMessage,
                                    onEdit = onEditMessage,
                                    onDelete = onDeleteMessage,
                                    onContinue = onContinueGenerating,
                                    onOpenPermissions = onOpenPermissions,
                                )
                            }

                            if (uiState.isAgentRunning || uiState.pendingConfirmation != null) {
                                item(key = "agent-live") {
                                    AgentLiveBlock(
                                        steps = uiState.agentSteps,
                                        pending = uiState.pendingConfirmation,
                                        onAllow = { alwaysForChat -> onRespondToConfirmation(true, alwaysForChat) },
                                        onDeny = { onRespondToConfirmation(false, false) },
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
                                            ) {
                                                followTail = true
                                                scrollToTail(true)
                                            }
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
                enabled = uiState.isSendingEnabled && !uiState.isPreparingSend && !uiState.isLoadingConversation,
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
