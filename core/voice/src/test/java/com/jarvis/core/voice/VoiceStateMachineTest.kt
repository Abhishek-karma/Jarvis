package com.jarvis.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VoiceStateMachineTest {

    private lateinit var sm: VoiceStateMachine

    @BeforeEach
    fun setUp() {
        sm = VoiceStateMachine()
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(VoiceSessionState.Idle, sm.state.value)
    }

    @Test
    fun `full happy path Idle - Listening - Thinking - Planning - Executing - Speaking - Idle`() {
        sm.onStartListening()
        assertTrue(sm.state.value is VoiceSessionState.Listening)

        sm.onSpeechRecognized("set an alarm")
        assertEquals(VoiceSessionState.Thinking("set an alarm"), sm.state.value)

        sm.onPlanning("set an alarm")
        assertEquals(VoiceSessionState.Planning("set an alarm"), sm.state.value)

        sm.onExecuting("set_alarm", "step 1")
        assertEquals(VoiceSessionState.Executing("set_alarm", "step 1"), sm.state.value)

        sm.onStartSpeaking("Alarm set for 7 AM")
        assertTrue(sm.state.value is VoiceSessionState.Speaking)

        sm.onPlaybackFinished(continueListening = false)
        assertEquals(VoiceSessionState.Idle, sm.state.value)
    }

    @Test
    fun `barge-in interrupts Speaking and transitions to Interrupted`() {
        sm.onStartSpeaking("Hello there")
        assertTrue(sm.state.value is VoiceSessionState.Speaking)

        sm.onInterrupted()
        assertEquals(VoiceSessionState.Interrupted, sm.state.value)
    }

    @Test
    fun `playback finished with continueListening returns to Listening`() {
        sm.onStartSpeaking("Done")
        sm.onPlaybackFinished(continueListening = true)
        assertTrue(sm.state.value is VoiceSessionState.Listening)
    }

    @Test
    fun `amplitude update only applies during Listening state`() {
        sm.onStartListening()
        sm.onAmplitudeUpdate(0.75f)
        assertEquals(0.75f, (sm.state.value as VoiceSessionState.Listening).amplitude)

        sm.onThinking("hello")
        sm.onAmplitudeUpdate(0.5f)
        assertEquals(VoiceSessionState.Thinking("hello"), sm.state.value)
    }

    @Test
    fun `amplitude is clamped to 0 to 1 range`() {
        sm.onStartListening()
        sm.onAmplitudeUpdate(5.0f)
        assertEquals(1.0f, (sm.state.value as VoiceSessionState.Listening).amplitude)

        sm.onAmplitudeUpdate(-3.0f)
        assertEquals(0.0f, (sm.state.value as VoiceSessionState.Listening).amplitude)
    }

    @Test
    fun `speaking progress is clamped and only applies during Speaking`() {
        sm.onStartSpeaking("test")
        sm.onSpeakingProgress(0.5f)
        assertEquals(0.5f, (sm.state.value as VoiceSessionState.Speaking).progress)

        sm.onSpeakingProgress(99f)
        assertEquals(1.0f, (sm.state.value as VoiceSessionState.Speaking).progress)

        sm.onThinking()
        sm.onSpeakingProgress(0.3f)
        assertTrue(sm.state.value is VoiceSessionState.Thinking)
    }

    @Test
    fun `WaitingForApproval pauses execution flow`() {
        sm.onExecuting("send_sms", "step 1")
        sm.onWaitingForApproval("send_sms", """{"to":"555-1234"}""")
        val state = sm.state.value as VoiceSessionState.WaitingForApproval
        assertEquals("send_sms", state.toolName)
        assertEquals("""{"to":"555-1234"}""", state.argsJson)
    }

    @Test
    fun `error with recoverable true allows return to Listening via onStartListening`() {
        sm.onError("Mic unavailable", recoverable = true)
        val errState = sm.state.value as VoiceSessionState.Error
        assertTrue(errState.recoverable)

        sm.onStartListening()
        assertTrue(sm.state.value is VoiceSessionState.Listening)
    }

    @Test
    fun `onCancelled transitions to Cancelled`() {
        sm.onStartListening()
        sm.onCancelled()
        assertEquals(VoiceSessionState.Cancelled, sm.state.value)
    }

    @Test
    fun `onStop always resets to Idle regardless of current state`() {
        sm.onStartSpeaking("hello")
        sm.onStop()
        assertEquals(VoiceSessionState.Idle, sm.state.value)

        sm.onError("boom")
        sm.onStop()
        assertEquals(VoiceSessionState.Idle, sm.state.value)

        sm.onWaitingForApproval("x", "{}")
        sm.onStop()
        assertEquals(VoiceSessionState.Idle, sm.state.value)
    }
}
