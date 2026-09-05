package com.jarvis.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.MessageRole
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Motion
import com.jarvis.core.designsystem.Spacing

/**
 * Voice Mode — a full-screen takeover on black: a center ~280dp sphere with radial
 * gradient #3B82F6 → #60A5FA → #93C5FD pulsing 0.95 ↔ 1.05 over 2s; bottom Mute /
 * Speak / End controls; white status text above them.
 *
 * Shares the chat's [ChatViewModel] so recording/transcription state stays continuous
 * with the composer's push-to-talk. Errors surface in an overlay snackbar (the chat
 * screen's snackbar isn't visible here), and leaving the screen stops the mic.
 */
@Composable
fun VoiceModeRoute(
    onEnd: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Voice mode has no visible chat scaffold, so it surfaces one-shot events itself.
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            val message =
                when (event) {
                    is ChatUiEvent.ShowError -> event.message
                    is ChatUiEvent.ShowNotice -> event.message
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Leaving voice mode (End or back) must not leave the mic hot.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopLiveSessionAndRecorder() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VoiceModeScreen(
            uiState = uiState,
            onToggleRecording = viewModel::toggleRecording,
            onSpeak = viewModel::speakLastResponse,
            onStopSpeaking = viewModel::stopSpeaking,
            onEnd = onEnd,
        )
        JarvisSnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = VOICE_SNACKBAR_CLEARANCE),
        )
    }
}

@Composable
fun VoiceModeScreen(
    uiState: ChatUiState,
    onToggleRecording: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onEnd: () -> Unit,
) {
    // Entrance: scale 0 → 1 + opacity 0 → 1 over a 400ms spring.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "voiceEntrance",
    )

    // Continuous pulse: scale 0.95 ↔ 1.05 over 2s ease-in-out.
    val pulse = rememberInfiniteTransition(label = "sphere")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(Motion.SPHERE_PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )

    val statusText =
        when {
            uiState.isRecording -> "Listening…"
            uiState.isTranscribing -> "Transcribing…"
            uiState.isPlayingAudio -> "Jarvis is speaking…"
            uiState.isStreaming -> "Jarvis is thinking…"
            else -> "Tap the mic and speak"
        }

    val lastUser =
        uiState.messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content
            .orEmpty()
    val lastAssistant =
        uiState.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.content
            .orEmpty()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(JarvisColors.Voice.takeover)
                .alpha(entrance)
                .semantics { contentDescription = "Voice conversation active, Jarvis is listening" },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(vertical = Spacing.huge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.6f))

            // The pulsing sphere with its glow.
            Box(
                modifier =
                    Modifier
                        .size(280.dp)
                        .scale(pulseScale * entrance.coerceIn(0.2f, 1f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(340.dp)) {
                    // Glow ≈ rgba(59,130,246,0.4) 0 0 80dp.
                    repeat(3) { ring ->
                        drawCircle(
                            color = JarvisColors.Voice.blue1.copy(alpha = 0.12f - ring * 0.04f),
                            radius = size.minDimension / 2f - ring * 18f,
                            center = Offset(size.width / 2f, size.height / 2f),
                            style = Stroke(width = 24f),
                        )
                    }
                }
                Canvas(modifier = Modifier.size(280.dp)) {
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        JarvisColors.Voice.blue3,
                                        JarvisColors.Voice.blue2,
                                        JarvisColors.Voice.blue1,
                                    ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension / 2f,
                            ),
                        radius = size.minDimension / 2f,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text(
                text = statusText,
                style = JarvisText.ConvTitle,
                color = Color.White.copy(alpha = 0.85f),
            )

            // Live transcript (last turn each way), quiet white-on-black.
            if (lastUser.isNotEmpty() || lastAssistant.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (lastUser.isNotEmpty()) {
                        Text(
                            text = lastUser,
                            style = JarvisText.Body,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                    if (lastAssistant.isNotEmpty()) {
                        Text(
                            text = lastAssistant,
                            style = JarvisText.BodyMedium,
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            // Bottom controls: Mute (left) · Speak (center) · End (right) — 44dp circles.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceControlButton(
                    icon = Icons.Default.Mic,
                    contentDescription = if (uiState.isRecording) "Stop listening" else "Start listening",
                    onClick = onToggleRecording,
                )
                Spacer(modifier = Modifier.width(Spacing.xxl))
                VoiceControlButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (uiState.isPlayingAudio) "Stop speaking" else "Read the response",
                    onClick = { if (uiState.isPlayingAudio) onStopSpeaking() else onSpeak() },
                )
                Spacer(modifier = Modifier.width(Spacing.xxl))
                VoiceControlButton(
                    icon = Icons.Default.Close,
                    contentDescription = "End voice mode",
                    onClick = onEnd,
                )
            }
        }
    }
}

/** How far the overlay snackbar floats above the bottom control row (padding + 56dp buttons). */
private val VOICE_SNACKBAR_CLEARANCE = 120.dp

/** 56dp circular control — white glyph on semi-transparent black. */
@Composable
private fun VoiceControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
