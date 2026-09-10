package com.jarvis.feature.chat

import com.jarvis.core.voice.AudioFormat
import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles STT audio recording, live voice session listening, and TTS playback
 * for the chat feature, keeping ChatViewModel focused on conversation and orchestration.
 */
@Singleton
class ChatVoiceManager @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val sttProvider: SttProvider,
    private val ttsProvider: TtsProvider,
) {
    private var liveSttSession: LiveSttSession? = null
    private var ttsJob: Job? = null

    fun isRecording(currentRecordingState: Boolean): Boolean = currentRecordingState

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
            stopRecording(
                scope = scope,
                mainDispatcher = mainDispatcher,
                ioDispatcher = ioDispatcher,
                onRecordingChanged = onRecordingChanged,
                onTranscribingChanged = onTranscribingChanged,
                onFinalText = onFinalText,
                onError = onError,
            )
        } else {
            startRecording(
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

    private fun startRecording(
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
                startLiveRecording(
                    session = liveSession,
                    scope = scope,
                    mainDispatcher = mainDispatcher,
                    onRecordingChanged = onRecordingChanged,
                    onTranscribingChanged = onTranscribingChanged,
                    onPartialText = onPartialText,
                    onFinalText = onFinalText,
                    onError = onError,
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

    private fun startLiveRecording(
        session: LiveSttSession,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher,
        onRecordingChanged: (Boolean) -> Unit,
        onTranscribingChanged: (Boolean) -> Unit,
        onPartialText: (String) -> Unit,
        onFinalText: (String, autoSend: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        liveSttSession = session
        onRecordingChanged(true)
        session.startListening(
            onPartial = { partial ->
                if (partial.isNotBlank()) {
                    onPartialText(partial)
                }
            },
            onResult = { finalText ->
                stopLiveSession(scope, mainDispatcher)
                onTranscribingChanged(false)
                if (finalText.isNotBlank()) {
                    onFinalText(finalText, true)
                }
            },
            onError = { message ->
                stopLiveSession(scope, mainDispatcher)
                onRecordingChanged(false)
                onTranscribingChanged(false)
                onError(message)
            },
        )
    }

    private fun stopLiveSession(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        liveSttSession?.let { session ->
            session.stopListening()
            scope.launch(mainDispatcher) { session.close() }
        }
        liveSttSession = null
    }

    private fun stopRecording(
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

        audioPlayer.stop()
        ttsJob?.cancel()

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
        audioPlayer.stop()
        ttsJob?.cancel()
        ttsJob = null
        onPlayingChanged(null)
    }

    fun release(scope: CoroutineScope, mainDispatcher: CoroutineDispatcher) {
        stopLiveSession(scope, mainDispatcher)
        audioRecorder.cancel()
        audioPlayer.stop()
        ttsJob?.cancel()
        ttsJob = null
    }

    companion object {
        const val LIVE_RESULT_TIMEOUT_MS = 500L
    }
}
