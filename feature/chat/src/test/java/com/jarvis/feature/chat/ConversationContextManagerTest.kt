package com.jarvis.feature.chat

import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationContextManagerTest {

    private val memoryRepo = mockk<MemoryRepository>()
    private val userPrefs = mockk<UserPreferencesRepository>()

    @Test
    fun `buildMemoryContext filters private memories when routing to cloud`() = runTest {
        every { userPrefs.memoryEnabled } returns flowOf(true)
        coEvery { memoryRepo.getActive() } returns listOf(
            Memory(
                id = "1",
                category = MemoryCategory.LONG_TERM_FACT,
                content = "Public fact: Likes coffee",
                source = "user",
                isPrivate = false,
                isActive = true,
            ),
            Memory(
                id = "2",
                category = MemoryCategory.LONG_TERM_FACT,
                content = "Private fact: Bank PIN 1234",
                source = "user",
                isPrivate = true,
                isActive = true,
            ),
        )

        val manager = ConversationContextManager(memoryRepo, userPrefs)

        // Cloud route: should exclude private memories
        val cloudContext = manager.buildMemoryContext(RoutingOverride.CLOUD)
        assertNotNull(cloudContext)
        assertTrue(cloudContext!!.contains("Likes coffee"))
        assertFalse(cloudContext.contains("Bank PIN"))

        // Local route: should include private memories
        val localContext = manager.buildMemoryContext(RoutingOverride.LOCAL)
        assertNotNull(localContext)
        assertTrue(localContext!!.contains("Likes coffee"))
        assertTrue(localContext.contains("Bank PIN"))
    }

    @Test
    fun `buildMemoryContext returns null when memory is disabled in preferences`() = runTest {
        every { userPrefs.memoryEnabled } returns flowOf(false)
        coEvery { memoryRepo.getActive() } returns listOf(
            Memory(
                id = "1",
                category = MemoryCategory.LONG_TERM_FACT,
                content = "Likes coffee",
                source = "user",
                isPrivate = false,
                isActive = true,
            ),
        )

        val manager = ConversationContextManager(memoryRepo, userPrefs)
        val context = manager.buildMemoryContext(RoutingOverride.CLOUD)
        assertNull(context)
    }

    @Test
    fun `buildMemoryContext prioritizes memories matching user query`() = runTest {
        every { userPrefs.memoryEnabled } returns flowOf(true)
        coEvery { memoryRepo.getActive() } returns listOf(
            Memory(
                id = "1",
                category = MemoryCategory.LONG_TERM_FACT,
                content = "Favorite programming language is Kotlin",
                source = "user",
                isPrivate = false,
                isActive = true,
            ),
            Memory(
                id = "2",
                category = MemoryCategory.LONG_TERM_FACT,
                content = "Pet dog name is Buster",
                source = "user",
                isPrivate = false,
                isActive = true,
            ),
        )

        val manager = ConversationContextManager(memoryRepo, userPrefs)
        val context = manager.buildMemoryContext(RoutingOverride.CLOUD, userQuery = "Can you write some Kotlin code?", maxMemories = 1)
        assertNotNull(context)
        assertTrue(context!!.contains("Kotlin"))
        assertFalse(context.contains("Buster"))
    }

    @Test
    fun `buildAssistantSystemPrompt builds rich layered prompt`() {
        val manager = ConversationContextManager(memoryRepo, userPrefs)
        val prompt = manager.buildAssistantSystemPrompt(
            memoryContext = "- [FACT]: Name is Alex",
            isLocal = false,
            isVoiceMode = true,
            planFirst = true,
            webToolsAvailable = true,
        )

        assertTrue(prompt.contains("=== 1. SYSTEM CORE ==="))
        assertTrue(prompt.contains("=== 2. RUNTIME CONTEXT ==="))
        assertTrue(prompt.contains("Voice Mode is currently ACTIVE"))
        assertTrue(prompt.contains("Plan-First Mode is ACTIVE"))
        assertTrue(prompt.contains("Name is Alex"))
    }
}
