package com.jarvis.core.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentTriggerTest {
    @Test
    fun `Jarvis prefix triggers agent mode`() {
        assertTrue(AgentTrigger.shouldUseAgent("Jarvis, check my battery"))
        assertTrue(AgentTrigger.shouldUseAgent("jarvis send a message to mom"))
    }

    @Test
    fun `plain questions do not trigger agent mode`() {
        assertFalse(AgentTrigger.shouldUseAgent("What is the capital of France?"))
        assertFalse(AgentTrigger.shouldUseAgent("Explain the difference between TCP and UDP"))
        assertFalse(AgentTrigger.shouldUseAgent(""))
        assertFalse(AgentTrigger.shouldUseAgent("   "))
    }

    @Test
    fun `action verbs trigger agent mode`() {
        assertTrue(AgentTrigger.shouldUseAgent("Create a reminder for 5pm"))
        assertTrue(AgentTrigger.shouldUseAgent("Check the battery"))
        assertTrue(AgentTrigger.shouldUseAgent("How much storage is free on this device"))
        assertTrue(AgentTrigger.shouldUseAgent("Schedule a meeting tomorrow"))
    }

    @Test
    fun `ambiguous verbs match at word boundaries not inside words`() {

        assertFalse(AgentTrigger.shouldUseAgent("What was that book called?"))
        assertFalse(AgentTrigger.shouldUseAgent("I can't recall the name"))

        assertTrue(AgentTrigger.shouldUseAgent("Please call the pharmacy"))
    }

    @Test
    fun `ambiguous verbs only fire in imperative position`() {
        assertFalse(AgentTrigger.shouldUseAgent("What do you call this?"))
        assertFalse(AgentTrigger.shouldUseAgent("can you make sense of this?"))
        assertFalse(AgentTrigger.shouldUseAgent("how do I find my saved wifi password"))
        assertTrue(AgentTrigger.shouldUseAgent("Call mom at 5"))
        assertTrue(AgentTrigger.shouldUseAgent("Make a note about the meeting"))
        assertTrue(AgentTrigger.shouldUseAgent("Write welcome message to file"))
        assertTrue(AgentTrigger.shouldUseAgent("what is today's date"))
        assertTrue(AgentTrigger.shouldUseAgent("what is the current time"))
    }
}
