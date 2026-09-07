package com.jarvis.core.network

import com.jarvis.core.common.ProviderConfig
import com.jarvis.core.common.ProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelProfileTest {

    @Test
    fun `resolveProvider for VISION selects provider with vision capability`() {
        val textOnly = ProviderConfig(
            id = "text",
            name = "Text Only",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api1.com",
        )
        val visionProvider = ProviderConfig(
            id = "vision",
            name = "Vision Model",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api2.com",
        )

        val resolved = ModelProfileResolver.resolveProvider(
            profile = ModelProfile.VISION,
            availableProviders = listOf(textOnly, visionProvider),
            capabilitiesResolver = {
                if (it.id == "vision") ProviderCapabilities(vision = true) else ProviderCapabilities()
            },
        )

        assertEquals("vision", resolved?.id)
    }

    @Test
    fun `resolveProvider for REASONING selects provider with reasoning capability`() {
        val standard = ProviderConfig(
            id = "standard",
            name = "Standard",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api1.com",
        )
        val reasoning = ProviderConfig(
            id = "reasoning",
            name = "Reasoning Model",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api2.com",
        )

        val resolved = ModelProfileResolver.resolveProvider(
            profile = ModelProfile.REASONING,
            availableProviders = listOf(standard, reasoning),
            capabilitiesResolver = {
                if (it.id == "reasoning") ProviderCapabilities(supportsReasoning = true) else ProviderCapabilities()
            },
        )

        assertEquals("reasoning", resolved?.id)
    }

    @Test
    fun `resolveProvider for FAST picks lowest average latency healthy provider`() {
        val slow = ProviderConfig(
            id = "slow",
            name = "Slow Provider",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api1.com",
        )
        val fast = ProviderConfig(
            id = "fast",
            name = "Fast Provider",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api2.com",
        )

        val tracker = ProviderHealthTracker()
        tracker.recordSuccess("slow", latencyMs = 1200L)
        tracker.recordSuccess("fast", latencyMs = 150L)

        val resolved = ModelProfileResolver.resolveProvider(
            profile = ModelProfile.FAST,
            availableProviders = listOf(slow, fast),
            healthTracker = tracker,
        )

        assertEquals("fast", resolved?.id)
    }
}
