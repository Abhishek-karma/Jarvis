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
}
