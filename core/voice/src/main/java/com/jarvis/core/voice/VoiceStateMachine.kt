package com.jarvis.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState
    data class Listening(val amplitude: Float = 0f) : VoiceSessionState
    data class Processing(val transcript: String) : VoiceSessionState
    data class Speaking(val utterance: String, val progress: Float = 0f) : VoiceSessionState
    data object Interrupted : VoiceSessionState
    data class Error(val message: String) : VoiceSessionState
}

/**
 * State machine governing continuous hands-free voice sessions with barge-in support.
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

    fun onSpeechRecognized(transcript: String) {
        _state.value = VoiceSessionState.Processing(transcript)
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

    fun onBargeIn() {
        _state.value = VoiceSessionState.Interrupted
        _state.value = VoiceSessionState.Listening(0f)
    }

    fun onPlaybackFinished(continueListening: Boolean = true) {
        _state.value = if (continueListening) VoiceSessionState.Listening(0f) else VoiceSessionState.Idle
    }

    fun onError(message: String) {
        _state.value = VoiceSessionState.Error(message)
    }

    fun onStop() {
        _state.value = VoiceSessionState.Idle
    }
}
