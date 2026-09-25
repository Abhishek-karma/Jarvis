package com.jarvis.feature.chat

import android.util.Log
import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SentenceSplitter
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.SttRecognitionError
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsResult
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

        closeLiveSession(scope, mainDispatcher)
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
        stopListeningInternal()

        listeningJob?.cancel()
        listeningJob = scope.launch(mainDispatcher) {
            voiceStateMachine.onStartListening()

            val session = liveSttSession ?: runCatching { sttProvider.startLiveSession() }.getOrNull()
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
                    stopListeningInternal()
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
                onError = { sttError ->
                    stopListeningInternal()
                    if (_isVoiceModeActive.value) {
                        val restartDelay = if (sttError.isRecoverable) 150L else 800L
                        scope.launch(mainDispatcher) {
                            delay(restartDelay)
                            if (_isVoiceModeActive.value) {
                                startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
                            }
                        }
                    } else {
                        voiceStateMachine.onError(sttError.message, recoverable = sttError.isRecoverable)
                        onError(sttError.message)
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

        stopListeningInternal()
        stopSpeakingInternal()

        val channel = Channel<String>(Channel.UNLIMITED)
        sentenceChannel = channel
        textBuffer.clear()

        ttsJob = scope.launch(mainDispatcher) {
            val audioQueue = Channel<TtsResult>(capacity = 6)
            var hasStartedSpeaking = false

            // Pipelined producer: synthesize upcoming chunks ahead of time
            val producerJob = launch(mainDispatcher) {
                try {
                    for (sentence in channel) {
                        if (!_isVoiceModeActive.value || _isSpeakerMuted.value) break
                        val cleanSentence = SentenceSplitter.cleanForSpeech(sentence)
                        if (cleanSentence.isBlank()) continue

                        val result = ttsProvider.synthesize(cleanSentence)
                        result.fold(
                            onSuccess = { ttsResult ->
                                if (_isVoiceModeActive.value && !_isSpeakerMuted.value) {
                                    audioQueue.send(ttsResult)
                                }
                            },
                            onFailure = { error ->
                                Log.w(TAG, "TTS synthesis failed for chunk: ${error.message}")
                            },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    // Normal channel termination
                } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    // Normal channel termination
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in TTS producer loop", t)
                } finally {
                    audioQueue.close()
                }
            }

            // Sequential playback: play audio chunks as they arrive from audioQueue
            try {
                for (ttsResult in audioQueue) {
                    if (!_isVoiceModeActive.value || _isSpeakerMuted.value) break
                    if (!hasStartedSpeaking) {
                        hasStartedSpeaking = true
                        voiceStateMachine.onStartSpeaking("")
                        activeSpeechCallback?.let { speechCb ->
                            activeErrorCallback?.let { errCb ->
                                startBargeInListening(scope, mainDispatcher, speechCb, errCb)
                            }
                        }
                    }
                    audioPlayer.play(ttsResult.audioData, ttsResult.format)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                activeErrorCallback?.invoke("TTS playback error: ${t.message}")
            } finally {
                producerJob.cancel()
                audioQueue.cancel()
                audioPlayer.stop()
                val isJobCancelled = coroutineContext[Job]?.isCancelled == true
                if (!isJobCancelled) {
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

        var splitIndex = SentenceSplitter.findSentenceSplitIndex(text, force)
        while (splitIndex > 0) {
            val sentence = textBuffer.substring(0, splitIndex).trim()
            textBuffer.delete(0, splitIndex)
            if (sentence.isNotBlank()) {
                sentenceChannel?.trySend(sentence)
            }
            val remaining = textBuffer.toString()
            splitIndex = SentenceSplitter.findSentenceSplitIndex(remaining, force)
        }
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

        prepareStreamingResponse(scope, mainDispatcher, onUserSpeechFinal, onError)
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
        activeSpeechCallback = onUserSpeechFinal
        activeErrorCallback = onError
        stopSpeakingInternal()
        voiceStateMachine.onInterrupted()
        if (_isVoiceModeActive.value) {
            scope.launch(mainDispatcher) {
                delay(50L)
                startLiveListening(scope, mainDispatcher, onUserSpeechFinal, onError)
            }
        }
    }

    /**
     * Starts listening in the background during assistant playback, allowing voice barge-in.
     */
    fun startBargeInListening(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onUserSpeechFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!_isVoiceModeActive.value) return

        listeningJob?.cancel()
        listeningJob = scope.launch(mainDispatcher) {
            val session = liveSttSession ?: runCatching { sttProvider.startLiveSession() }.getOrNull()
            if (session == null) return@launch

            liveSttSession = session
            session.setRmsListener {
                // Don't update amplitude in Speaking state to avoid bouncing UI
            }

            session.startListening(
                onPartial = { partial ->
                    if (partial.isNotBlank() && voiceStateMachine.state.value is VoiceSessionState.Speaking) {
                        interruptSpeaking(scope, mainDispatcher, onUserSpeechFinal, onError)
                    }
                },
                onResult = { finalText ->
                    if (finalText.isNotBlank() && voiceStateMachine.state.value is VoiceSessionState.Speaking) {
                        interruptSpeaking(scope, mainDispatcher, onUserSpeechFinal, onError)
                    }
                },
                onError = {
                    // Ignore STT errors during speaking so we don't disrupt playback or loop
                }
            )
        }
    }

    /**
     * Helper to parse and build a clear, friendly confirmation question for tool runs.
     */
    fun getConfirmationQuestion(toolName: String, argsJson: String): String {
        return when (toolName) {
            "place_call", "call" -> {
                val name = parseArg(argsJson, "name") ?: parseArg(argsJson, "contact") ?: "them"
                "I found $name's mobile number. Do you want me to call them?"
            }
            "send_sms", "send_message" -> {
                val name = parseArg(argsJson, "name") ?: parseArg(argsJson, "contact") ?: "them"
                val message = parseArg(argsJson, "message") ?: parseArg(argsJson, "text") ?: ""
                if (message.isNotBlank()) {
                    "This will send '$message' to $name. Should I send it?"
                } else {
                    "Should I send a message to $name?"
                }
            }
            else -> {
                val friendlyToolName = toolName.replace("_", " ")
                "I need to run $friendlyToolName. Is that okay?"
            }
        }
    }

    private fun parseArg(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return regex.find(json)?.groupValues?.get(1)
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
                        closeLiveSession(scope, mainDispatcher)
                        onRecordingChanged(false)
                        onTranscribingChanged(false)
                        if (finalText.isNotBlank()) onFinalText(finalText, true)
                    },
                    onError = { sttError ->
                        closeLiveSession(scope, mainDispatcher)
                        onRecordingChanged(false)
                        onTranscribingChanged(false)
                        onError(sttError.message)
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

    private fun stopListeningInternal() {
        listeningJob?.cancel()
        listeningJob = null
        liveSttSession?.cancelListening()
    }

    private fun closeLiveSession(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        listeningJob?.cancel()
        listeningJob = null
        liveSttSession?.let { session ->
            session.cancelListening()
            scope.launch(mainDispatcher) { session.close() }
        }
        liveSttSession = null
    }

    fun stopLiveSessionAndRecorder(
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onResetState: () -> Unit,
    ) {
        closeLiveSession(scope, mainDispatcher)
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

        val speechText = SentenceSplitter.cleanForSpeech(content)
        if (speechText.isBlank()) {
            return
        }

        ttsJob = scope.launch(mainDispatcher) {
            onPlayingChanged(messageId)
            try {
                ttsProvider.speak(speechText).onFailure { e ->
                    onError("Speech playback failed: ${e.message ?: "TTS error"}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("Speech error: ${t.message ?: "Unknown error"}")
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
        ttsProvider.stop()
        audioPlayer.stop()
        ttsJob?.cancel()
        ttsJob = null
    }

    fun release(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        _isVoiceModeActive.value = false
        closeLiveSession(scope, mainDispatcher)
        audioRecorder.cancel()
        ttsProvider.close()
        stopSpeakingInternal()
        voiceStateMachine.onStop()
    }

    companion object {
        private const val TAG = "ChatVoiceManager"
        const val LIVE_RESULT_TIMEOUT_MS = 500L
    }
}
