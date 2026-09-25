package com.jarvis.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState
    data class Listening(val amplitude: Float = 0f) : VoiceSessionState
    data class Thinking(val query: String = "") : VoiceSessionState
    data class Planning(val query: String = "") : VoiceSessionState
    data class Executing(val toolName: String = "", val step: String = "") : VoiceSessionState
    data class WaitingForApproval(val toolName: String, val argsJson: String) : VoiceSessionState
    data class Speaking(val utterance: String, val progress: Float = 0f) : VoiceSessionState
    data class Error(val message: String, val recoverable: Boolean = true) : VoiceSessionState
    data object Cancelled : VoiceSessionState
    data object Interrupted : VoiceSessionState
}

/**
 * State machine governing explicit continuous hands-free voice sessions with barge-in support.
 */
@Singleton
class VoiceStateMachine @Inject constructor() {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    fun onStartListening() {
        _state.value = VoiceSessionState.Listening(0f)
    }

    fun onAmplitudeUpdate(amplitude: Float) {
        val current = _state.value
        if (current is VoiceSessionState.Listening) {
            _state.value = current.copy(amplitude = amplitude.coerceIn(0f, 1f))
        }
    }

    fun onThinking(query: String = "") {
        _state.value = VoiceSessionState.Thinking(query)
    }

    fun onPlanning(query: String = "") {
        _state.value = VoiceSessionState.Planning(query)
    }

    fun onExecuting(toolName: String, step: String = "") {
        _state.value = VoiceSessionState.Executing(toolName = toolName, step = step)
    }

    fun onWaitingForApproval(toolName: String, argsJson: String) {
        _state.value = VoiceSessionState.WaitingForApproval(toolName = toolName, argsJson = argsJson)
    }

    fun onSpeechRecognized(transcript: String) {
        _state.value = VoiceSessionState.Thinking(transcript)
    }

    fun onStartSpeaking(utterance: String) {
        _state.value = VoiceSessionState.Speaking(utterance)
    }

    fun onSpeakingProgress(progress: Float) {
        val current = _state.value
        if (current is VoiceSessionState.Speaking) {
            _state.value = current.copy(progress = progress.coerceIn(0f, 1f))
        }
    }

    fun onInterrupted() {
        _state.value = VoiceSessionState.Interrupted
    }

    fun onPlaybackFinished(continueListening: Boolean = true) {
        _state.value = if (continueListening) VoiceSessionState.Listening(0f) else VoiceSessionState.Idle
    }

    fun onError(message: String, recoverable: Boolean = true) {
        _state.value = VoiceSessionState.Error(message, recoverable)
    }

    fun onCancelled() {
        _state.value = VoiceSessionState.Cancelled
    }

    fun onStop() {
        _state.value = VoiceSessionState.Idle
    }
}

