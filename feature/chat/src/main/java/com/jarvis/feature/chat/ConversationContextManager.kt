package com.jarvis.feature.chat

import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages conversation context building, active memories injection,
 * and automatic context/preference learning.
 */
@Singleton
class ConversationContextManager @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val userPreferences: UserPreferencesRepository,
) {
    suspend fun buildMemoryContext(activeRoute: RoutingOverride): String? {
        val memoryEnabled = userPreferences.memoryEnabled.first()
        if (!memoryEnabled) return null
        val activeMemories = memoryRepository.getActive()
        val isLocal = activeRoute == RoutingOverride.LOCAL
        val eligibleMemories = if (isLocal) activeMemories else activeMemories.filterNot { it.isPrivate }
        return if (eligibleMemories.isNotEmpty()) {
            eligibleMemories.joinToString("\n") { "- [${it.category.name}]: ${it.content}" }
        } else null
    }

    fun buildAssistantSystemPrompt(memoryContext: String?): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm", Locale.getDefault()).format(Date())
        return buildString {
            append("You are Jarvis, an intelligent, helpful personal AI assistant running on an Android mobile device.")
            append("\nCurrent device date and time: $now.")
            if (!memoryContext.isNullOrBlank()) {
                append("\n\n[Assistant Memory & User Context]\n")
                append(memoryContext)
                append("\n\nUse this context to remember the user's details, preferences, and background to personalize your responses.")
            }
        }
    }

    suspend fun extractAndSaveLearnedContext(userText: String) {
        val memoryEnabled = userPreferences.memoryEnabled.first()
        if (!memoryEnabled) return
        val trimmed = userText.trim()

        // Structured learning definitions: pattern + normalized fact extractor + category
        data class ExtractionRule(
            val regex: Regex,
            val category: MemoryCategory,
            val format: (MatchResult) -> String,
        )

        val rules = listOf(
            ExtractionRule(
                regex = Regex("(?i)\\bmy name is ([a-zA-Z\\s]{2,30})\\b"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User's name is ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bcall me ([a-zA-Z\\s]{2,30})\\b"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User prefers to be called ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bi live in ([a-zA-Z\\s,]{2,40})\\b"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User lives in ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bmy favorite (\\w+) is ([^,.!?]+)"),
                category = MemoryCategory.CONVERSATION_CONTEXT,
                format = { m -> "User's favorite ${m.groupValues[1].trim()} is ${m.groupValues[2].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bi prefer ([^,.!?]+)"),
                category = MemoryCategory.CONVERSATION_CONTEXT,
                format = { m -> "User prefers ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bremember that ([^,.!?]+)"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> m.groupValues[1].trim() },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bmy email is ([^\\s@]+@[^\\s@]+\\.[^\\s@]+)"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User's email is ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bi am allergic to ([^,.!?]+)"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User is allergic to ${m.groupValues[1].trim()}" },
            ),
            ExtractionRule(
                regex = Regex("(?i)\\bi work as (?:a|an)?\\s*([a-zA-Z\\s]{2,30})\\b"),
                category = MemoryCategory.LONG_TERM_FACT,
                format = { m -> "User works as ${m.groupValues[1].trim()}" },
            ),
        )

        val existing = memoryRepository.getActive()
        for (rule in rules) {
            val match = rule.regex.find(trimmed)
            if (match != null) {
                val fact = rule.format(match).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                if (existing.none { it.content.equals(fact, ignoreCase = true) }) {
                    memoryRepository.upsert(
                        Memory(
                            category = rule.category,
                            content = fact,
                            source = "conversation_learning",
                            confidence = 0.95f,
                            timestamp = System.currentTimeMillis(),
                            isPrivate = false,
                            isActive = true,
                        ),
                    )
                }
                break
            }
        }
    }
}
