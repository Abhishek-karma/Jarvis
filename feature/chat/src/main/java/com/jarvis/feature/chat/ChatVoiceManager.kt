package com.jarvis.feature.chat

import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsVoice
import com.jarvis.core.voice.VoiceSessionState
import com.jarvis.core.voice.VoiceStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles continuous Voice Mode loop, STT live listening, state machine coordination,
 * and TTS playback for the chat and voice features.
 */
@Singleton
class ChatVoiceManager @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val sttProvider: SttProvider,
    private val ttsProvider: TtsProvider,
    private val voiceStateMachine: VoiceStateMachine,
) {
    val voiceState: StateFlow<VoiceSessionState> = voiceStateMachine.state

    private val _isVoiceModeActive = MutableStateFlow(false)
    val isVoiceModeActive: StateFlow<Boolean> = _isVoiceModeActive.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false)
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private var liveSttSession: LiveSttSession? = null
    private var ttsJob: Job? = null
    private var listeningJob: Job? = null

    private var activeSpeechCallback: ((String) -> Unit)? = null
    private var activeErrorCallback: ((String) -> Unit)? = null

    fun isRecording(currentRecordingState: Boolean): Boolean = currentRecordingState

    /**
     * Starts continuous hands-free Voice Mode session.
     */
    fun startVoiceMode(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        _isVoiceModeActive.value = true
        _isSpeakerMuted.value = false
        activeSpeechCallback = onUserSpeechFinal
        activeErrorCallback = onError

        startLiveListening(
            scope = scope,
            mainDispatcher = mainDispatcher,
            onUserSpeechFinal = onUserSpeechFinal,
            onError = onError,
        )
    }

    /**
     * Stops continuous hands-free Voice Mode session and cleans up resources.
     */
    fun stopVoiceMode(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
    ) {
        _isVoiceModeActive.value = false
        activeSpeechCallback = null
        activeErrorCallback = null

        stopLiveSession(scope, mainDispatcher)
        stopSpeakingInternal()
        voiceStateMachine.onStop()
    }

    /**
     * Toggles whether the assistant's voice is muted in Voice Mode.
     */
    fun toggleSpeakerMute(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onSpeechResume: () -> Unit,
    ) {
        val newMuted = !_isSpeakerMuted.value
        _isSpeakerMuted.value = newMuted
        if (newMuted && voiceStateMachine.state.value is VoiceSessionState.Speaking) {
            stopSpeakingInternal()
            voiceStateMachine.onPlaybackFinished(continueListening = true)
            onSpeechResume()
        }
    }

    /**
     * Starts live STT listening loop for continuous Voice Mode.
     */
    fun startLiveListening(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!_isVoiceModeActive.value) return

        stopSpeakingInternal()
        stopLiveSession(scope, mainDispatcher)

        listeningJob?.cancel()
        listeningJob = scope.launch(mainDispatcher) {
            voiceStateMachine.onStartListening()

            val session = runCatching { sttProvider.startLiveSession() }.getOrNull()
            if (session == null) {
                voiceStateMachine.onError("Speech recognition not available", recoverable = false)
                onError("Speech recognition not available on this device")
                return@launch
            }

            liveSttSession = session
            session.setRmsListener { rms ->
                voiceStateMachine.onAmplitudeUpdate(rms)
            }

            session.startListening(
                onPartial = { partial ->
                    if (partial.isNotBlank()) {
                        // Partial transcription received
                    }
                },
                onResult = { finalText ->
                    stopLiveSession(scope, mainDispatcher)
                    if (finalText.isNotBlank()) {
                        voiceStateMachine.onSpeechRecognized(finalText)
                        onUserSpeechFinal(finalText)
                    } else if (_isVoiceModeActive.value) {
                        scope.launch(mainDispatcher) {
                            delay(100L)
                            if (_isVoiceModeActive.value && voiceStateMachine.state.value is VoiceSessionState.Listening) {
                                startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
                            }
                        }
                    }
                },
                onError = { message ->
                    stopLiveSession(scope, mainDispatcher)
                    val isTransient = message.contains("No speech", ignoreCase = true) ||
                        message.contains("timed out", ignoreCase = true) ||
                        message.contains("busy", ignoreCase = true)

                    if (isTransient && _isVoiceModeActive.value) {
                        scope.launch(mainDispatcher) {
                            delay(150L)
                            if (_isVoiceModeActive.value && (voiceStateMachine.state.value is VoiceSessionState.Listening || voiceStateMachine.state.value is VoiceSessionState.Idle)) {
                                startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
                            }
                        }
                    } else {
                        voiceStateMachine.onError(message, recoverable = isTransient)
                        onError(message)
                    }
                },
            )
        }
    }

    /**
     * Speaks the assistant's final response and seamlessly resumes listening when finished.
     */
    fun speakAssistantResponse(
        text: String,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!_isVoiceModeActive.value) return

        if (_isSpeakerMuted.value || text.isBlank()) {
            voiceStateMachine.onPlaybackFinished(continueListening = true)
            startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
            return
        }

        stopLiveSession(scope, mainDispatcher)
        stopSpeakingInternal()

        ttsJob = scope.launch(mainDispatcher) {
            voiceStateMachine.onStartSpeaking(text)
            try {
                val result = ttsProvider.synthesize(text, TtsVoice.NOVA)
                result.fold(
                    onSuccess = { ttsResult ->
                        if (_isVoiceModeActive.value) {
                            audioPlayer.play(ttsResult.audioData, ttsResult.format.extension)
                        }
                    },
                    onFailure = { error ->
                        onError("TTS failed: ${error.message}")
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("TTS playback error: ${t.message}")
            } finally {
                if (_isVoiceModeActive.value) {
                    voiceStateMachine.onPlaybackFinished(continueListening = true)
                    startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
                } else {
                    voiceStateMachine.onPlaybackFinished(continueListening = false)
                }
            }
        }
    }

    /**
     * User interruption during TTS (barge-in): stops speech and immediately starts listening.
     */
    fun interruptSpeaking(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopSpeakingInternal()
        voiceStateMachine.onInterrupted()
        if (_isVoiceModeActive.value) {
            scope.launch(mainDispatcher) {
                delay(50L)
                startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
            }
        }
    }

    fun onAgentPlanning(query: String = "") {
        if (_isVoiceModeActive.value) {
            voiceStateMachine.onPlanning(query)
        }
    }

    fun onAgentExecuting(toolName: String, step: String = "") {
        if (_isVoiceModeActive.value) {
            voiceStateMachine.onExecuting(toolName, step)
        }
    }

    fun onAgentWaitingForApproval(toolName: String, argsJson: String) {
        if (_isVoiceModeActive.value) {
            voiceStateMachine.onWaitingForApproval(toolName, argsJson)
        }
    }

    fun onAgentCancelled() {
        if (_isVoiceModeActive.value) {
            voiceStateMachine.onCancelled()
        }
    }

    fun onAgentError(message: String) {
        if (_isVoiceModeActive.value) {
            voiceStateMachine.onError(message, recoverable = true)
        }
    }

    /**
     * Single-shot recording for regular chat screen (push-to-talk).
     */
    fun toggleRecording(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        ioDispatcher: CoroutineDispatcher,
        isCurrentlyRecording: Boolean,
        onRecordingChanged: (Boolean) -> Unit,
        onTranscribingChanged: (Boolean) -> Unit,
        onPartialText: (String) -> Unit,
        onFinalText: (String, autoSend: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isCurrentlyRecording) {
            stopSingleShotRecording(
                scope = scope,
                mainDispatcher = mainDispatcher,
                ioDispatcher = ioDispatcher,
                onRecordingChanged = onRecordingChanged,
                onTranscribingChanged = onTranscribingChanged,
                onFinalText = onFinalText,
                onError = onError,
            )
        } else {
            startSingleShotRecording(
                scope = scope,
                mainDispatcher = mainDispatcher,
                onRecordingChanged = onRecordingChanged,
                onTranscribingChanged = onTranscribingChanged,
                onPartialText = onPartialText,
                onFinalText = onFinalText,
                onError = onError,
            )
        }
    }

    private fun startSingleShotRecording(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onRecordingChanged: (Boolean) -> Unit,
        onTranscribingChanged: (Boolean) -> Unit,
        onPartialText: (String) -> Unit,
        onFinalText: (String, autoSend: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch(mainDispatcher) {
            val liveSession = runCatching { sttProvider.startLiveSession() }.getOrNull()
            if (liveSession != null) {
                liveSttSession = liveSession
                onRecordingChanged(true)
                liveSession.startListening(
                    onPartial = { partial ->
                        if (partial.isNotBlank()) onPartialText(partial)
                    },
                    onResult = { finalText ->
                        stopLiveSession(scope, mainDispatcher)
                        onRecordingChanged(false)
                        onTranscribingChanged(false)
                        if (finalText.isNotBlank()) onFinalText(finalText, true)
                    },
                    onError = { message ->
                        stopLiveSession(scope, mainDispatcher)
                        onRecordingChanged(false)
                        onTranscribingChanged(false)
                        onError(message)
                    },
                )
                return@launch
            }

            try {
                audioRecorder.start()
                onRecordingChanged(true)
            } catch (e: SecurityException) {
                onError("Microphone permission required")
            } catch (e: IllegalStateException) {
                onError(e.message ?: "Could not start recording")
            }
        }
    }

    private fun stopSingleShotRecording(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        ioDispatcher: CoroutineDispatcher,
        onRecordingChanged: (Boolean) -> Unit,
        onTranscribingChanged: (Boolean) -> Unit,
        onFinalText: (String, autoSend: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (liveSttSession != null) {
            onRecordingChanged(false)
            onTranscribingChanged(true)
            liveSttSession?.stopListening()
            scope.launch(mainDispatcher) {
                liveSttSession?.close()
                liveSttSession = null
                delay(LIVE_RESULT_TIMEOUT_MS)
                onTranscribingChanged(false)
            }
            return
        }

        scope.launch(mainDispatcher) {
            onRecordingChanged(false)
            onTranscribingChanged(true)
            val audioData = withContext(ioDispatcher) {
                runCatching { audioRecorder.stop() }.getOrNull()
            } ?: run {
                onTranscribingChanged(false)
                onError("Nothing was recorded")
                return@launch
            }

            sttProvider
                .transcribe(audioData, AudioFormat.WAV)
                .onSuccess { result ->
                    onTranscribingChanged(false)
                    if (result.text.isNotBlank()) {
                        onFinalText(result.text, true)
                    }
                }.onFailure { e ->
                    onTranscribingChanged(false)
                    onError("Transcription failed: ${e.message}")
                }
        }
    }

    private fun stopLiveSession(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        listeningJob?.cancel()
        listeningJob = null
        liveSttSession?.let { session ->
            session.stopListening()
            scope.launch(mainDispatcher) { session.close() }
        }
        liveSttSession = null
    }

    fun stopLiveSessionAndRecorder(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onResetState: () -> Unit,
    ) {
        stopLiveSession(scope, mainDispatcher)
        onResetState()
        audioRecorder.cancel()
    }

    fun speakMessage(
        messageId: String,
        content: String,
        currentPlayingId: String?,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onPlayingChanged: (String?) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (currentPlayingId == messageId) {
            stopSpeaking(onPlayingChanged)
            return
        }

        stopSpeakingInternal()

        ttsJob = scope.launch(mainDispatcher) {
            onPlayingChanged(messageId)
            try {
                ttsProvider
                    .synthesize(content, TtsVoice.NOVA)
                    .onSuccess { result ->
                        audioPlayer.play(result.audioData, result.format.extension)
                    }.onFailure { e ->
                        onError("TTS failed: ${e.message}")
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                onPlayingChanged(null)
            }
        }
    }

    fun stopSpeaking(onPlayingChanged: (String?) -> Unit) {
        stopSpeakingInternal()
        onPlayingChanged(null)
    }

    private fun stopSpeakingInternal() {
        audioPlayer.stop()
        ttsJob?.cancel()
        ttsJob = null
    }

    fun release(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        _isVoiceModeActive.value = false
        stopLiveSession(scope, mainDispatcher)
        audioRecorder.cancel()
        stopSpeakingInternal()
        voiceStateMachine.onStop()
    }

    companion object {
        const val LIVE_RESULT_TIMEOUT_MS = 500L
    }
}

