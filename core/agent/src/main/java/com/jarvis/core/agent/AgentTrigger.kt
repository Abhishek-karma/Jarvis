package com.jarvis.core.agent

/**
 * Auto-detect agent mode: a "Jarvis," prefix or a request built around action verbs. Pure and unit-tested; the chat layer routes to the
 * AgentEngine when this fires (and the provider supports tools).
 */
object AgentTrigger {
    private val actionVerbs =
        listOf(
            "send",
            "create",
            "make",
            "delete",
            "remove",
            "find",
            "set up",
            "setup",
            "schedule",
            "book",
            "call",
            "turn on",
            "turn off",
            "adjust",
            "open",
            "check the battery",
            "storage",
            "network state",
            "what time",
            "rename",
            "move",
            "download",
            "search for",
        )

    fun shouldUseAgent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val afterPrefix = trimmed.getOrNull("jarvis".length)
        if (trimmed.startsWith("jarvis", ignoreCase = true) &&
            (afterPrefix == null || afterPrefix == ',' || afterPrefix.isWhitespace())
        ) {
            return true
        }
        val lower = trimmed.lowercase()
        return actionVerbs.any { lower.contains(it) }
    }
}
