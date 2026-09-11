package com.jarvis.feature.chat

import com.jarvis.core.voice.AudioPlayer
import com.jarvis.core.voice.AudioRecorder
import com.jarvis.core.voice.LiveSttSession
import com.jarvis.core.voice.SttProvider
import com.jarvis.core.voice.TtsFormat
import com.jarvis.core.voice.TtsProvider
import com.jarvis.core.voice.TtsResult
import com.jarvis.core.voice.TtsVoice
import com.jarvis.core.voice.VoiceSessionState
import com.jarvis.core.voice.VoiceStateMachine
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChatVoiceManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var sttProvider: SttProvider
    private lateinit var ttsProvider: TtsProvider
    private lateinit var voiceStateMachine: VoiceStateMachine
    private lateinit var liveSttSession: LiveSttSession
    private lateinit var voiceManager: ChatVoiceManager

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        audioRecorder = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        sttProvider = mockk(relaxed = true)
        ttsProvider = mockk(relaxed = true)
        voiceStateMachine = VoiceStateMachine()
        liveSttSession = mockk(relaxed = true)

        coEvery { sttProvider.startLiveSession() } returns liveSttSession

        voiceManager = ChatVoiceManager(
            audioRecorder = audioRecorder,
            audioPlayer = audioPlayer,
            sttProvider = sttProvider,
            ttsProvider = ttsProvider,
            voiceStateMachine = voiceStateMachine,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startVoiceMode starts listening and updates voice state to Listening`() = testScope.runTest {
        var speechResult: String? = null
        voiceManager.startVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = { speechResult = it },
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        assertTrue(voiceManager.isVoiceModeActive.value)
        assertTrue(voiceStateMachine.state.value is VoiceSessionState.Listening)
        assertFalse(voiceManager.isSpeakerMuted.value)
    }

    @Test
    fun `stt result transitions to thinking and invokes speech callback`() = testScope.runTest {
        val resultSlot = slot<(String) -> Unit>()
        every {
            liveSttSession.startListening(
                onPartial = any(),
                onResult = capture(resultSlot),
                onError = any(),
            )
        } just Runs

        var speechResult: String? = null
        voiceManager.startVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = { speechResult = it },
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        resultSlot.captured.invoke("Turn on the lights")
        testScheduler.advanceUntilIdle()

        assertEquals("Turn on the lights", speechResult)
        val state = voiceStateMachine.state.value
        assertTrue(state is VoiceSessionState.Thinking)
        assertEquals("Turn on the lights", (state as VoiceSessionState.Thinking).query)
    }

    @Test
    fun `speaking assistant response synthesizes tts and automatically loops back to listening`() = testScope.runTest {
        coEvery { ttsProvider.synthesize("Done!", TtsVoice.NOVA, TtsFormat.MP3) } returns Result.success(
            TtsResult(
                audioData = byteArrayOf(1, 2, 3),
                format = TtsFormat.MP3,
            )
        )

        voiceManager.startVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = {},
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        voiceManager.speakAssistantResponse(
            text = "Done!",
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = {},
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        coVerify { audioPlayer.play(byteArrayOf(1, 2, 3), "mp3") }
        // After playback in voice mode, automatically transitions back to Listening
        assertTrue(voiceStateMachine.state.value is VoiceSessionState.Listening)
    }

    @Test
    fun `interruptSpeaking stops playback and immediately resumes listening`() = testScope.runTest {
        coEvery { ttsProvider.synthesize(any(), any(), any()) } returns Result.success(
            TtsResult(
                audioData = byteArrayOf(1, 2, 3),
                format = TtsFormat.MP3,
            )
        )

        voiceManager.startVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = {},
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        voiceManager.interruptSpeaking(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = {},
            onError = {},
        )
        testScheduler.advanceUntilIdle()

        coVerify { audioPlayer.stop() }
        assertTrue(voiceStateMachine.state.value is VoiceSessionState.Listening)
    }

    @Test
    fun `stopVoiceMode releases resources and resets state to Idle`() = testScope.runTest {
        voiceManager.startVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
            onUserSpeechFinal = {},
            onError = {},
        )
        testScheduler.advanceUntilIdle()
        assertTrue(voiceManager.isVoiceModeActive.value)

        voiceManager.stopVoiceMode(
            scope = this,
            mainDispatcher = testDispatcher,
        )
        testScheduler.advanceUntilIdle()

        assertFalse(voiceManager.isVoiceModeActive.value)
        assertEquals(VoiceSessionState.Idle, voiceStateMachine.state.value)
    }
}
