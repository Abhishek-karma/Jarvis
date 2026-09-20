package com.jarvis.feature.chat

import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsFormat
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsVoice
import com.jarvis.core.voice.VoiceSessionState
import com.jarvis.core.voice.VoiceStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * streaming chunked TTS playback, and audio control.
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

    private var sentenceChannel: Channel<String>? = null
    private val textBuffer = StringBuilder()

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
     * Starts live STT listening loop for continuous hands-free Voice Mode.
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
                        message.contains("busy", ignoreCase = true) ||
                        message.contains("no match", ignoreCase = true) ||
                        message.contains("7") || message.contains("6") || message.contains("8") || message.contains("9")

                    if (_isVoiceModeActive.value) {
                        val restartDelay = if (isTransient) 150L else 800L
                        scope.launch(mainDispatcher) {
                            delay(restartDelay)
                            if (_isVoiceModeActive.value) {
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
     * Prepares chunked streaming TTS playback as LLM tokens stream in.
     * Speech begins on the very first completed sentence without waiting for the full response.
     */
    fun prepareStreamingResponse(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        if (!_isVoiceModeActive.value) return

        onUserSpeechFinal?.let { activeSpeechCallback = it }
        onError?.let { activeErrorCallback = it }

        stopLiveSession(scope, mainDispatcher)
        stopSpeakingInternal()

        val channel = Channel<String>(Channel.UNLIMITED)
        sentenceChannel = channel
        textBuffer.clear()

        ttsJob = scope.launch(mainDispatcher) {
            voiceStateMachine.onStartSpeaking("")
            try {
                for (sentence in channel) {
                    if (!_isVoiceModeActive.value || _isSpeakerMuted.value) break
                    if (sentence.isBlank()) continue

                    val result = ttsProvider.synthesize(sentence, TtsVoice.NOVA, TtsFormat.MP3)
                    result.fold(
                        onSuccess = { ttsResult ->
                            if (_isVoiceModeActive.value && !_isSpeakerMuted.value) {
                                audioPlayer.play(ttsResult.audioData, ttsResult.format.extension)
                            }
                        },
                        onFailure = { error ->
                            activeErrorCallback?.invoke("TTS error: ${error.message}")
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                activeErrorCallback?.invoke("TTS playback error: ${t.message}")
            } finally {
                if (_isVoiceModeActive.value) {
                    voiceStateMachine.onPlaybackFinished(continueListening = true)
                    activeSpeechCallback?.let { speechCb ->
                        activeErrorCallback?.let { errCb ->
                            startLiveListening(scope, mainDispatcher, speechCb, errCb)
                        }
                    }
                } else {
                    voiceStateMachine.onPlaybackFinished(continueListening = false)
                }
            }
        }
    }

    /**
     * Called as LLM tokens arrive to chunk and stream speech sentence-by-sentence.
     */
    fun onStreamingToken(token: String) {
        if (!_isVoiceModeActive.value || _isSpeakerMuted.value) return
        textBuffer.append(token)
        processSentenceBuffer(force = false)
    }

    /**
     * Called when LLM streaming completes to flush any remaining text to TTS.
     */
    fun onStreamingComplete() {
        if (!_isVoiceModeActive.value) return
        processSentenceBuffer(force = true)
        sentenceChannel?.close()
    }

    private fun processSentenceBuffer(force: Boolean) {
        val text = textBuffer.toString()
        if (text.isBlank()) return

        var splitIndex = findSentenceSplitIndex(text, force)
        while (splitIndex > 0) {
            val sentence = textBuffer.substring(0, splitIndex).trim()
            textBuffer.delete(0, splitIndex)
            if (sentence.isNotBlank()) {
                sentenceChannel?.trySend(sentence)
            }
            val remaining = textBuffer.toString()
            splitIndex = findSentenceSplitIndex(remaining, force)
        }
    }

    private fun findSentenceSplitIndex(text: String, force: Boolean): Int {
        if (force) return text.length

        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '?' || c == '!' || c == '\n') {
                if (i + 1 < text.length && text[i + 1].isWhitespace()) {
                    return i + 1
                } else if (i + 1 == text.length && text.length >= 15) {
                    return i + 1
                }
            }
        }

        // If no punctuation found but buffer is long (>90 chars), split at last space
        if (text.length > 90) {
            val lastSpace = text.lastIndexOf(' ')
            if (lastSpace > 20) {
                return lastSpace + 1
            }
        }
        return -1
    }

    /**
     * Speaks the assistant's final response sentence-by-sentence and seamlessly resumes listening when finished.
     */
    fun speakAssistantResponse(
        text: String,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!_isVoiceModeActive.value) return
        activeSpeechCallback = onUserSpeechFinal
        activeErrorCallback = onError

        if (_isSpeakerMuted.value || text.isBlank()) {
            voiceStateMachine.onPlaybackFinished(continueListening = true)
            startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
            return
        }

        prepareStreamingResponse(scope, mainDispatcher)
        onStreamingToken(text)
        onStreamingComplete()
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
        sentenceChannel?.close()
        sentenceChannel = null
        textBuffer.clear()
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
