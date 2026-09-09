package com.jarvis.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.designsystem.JarvisSnackbarHost
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
                onRetryMessage = viewModel::retryMessage,
                onEditMessage = viewModel::editMessage,
                onDeleteMessage = viewModel::deleteMessage,
                onContinueGenerating = viewModel::continueGenerating,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
