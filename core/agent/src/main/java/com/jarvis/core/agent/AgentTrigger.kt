package com.jarvis.core.agent


object AgentTrigger {
    /** Matched anywhere at word boundaries — distinctive enough to be safe. */
    private val anywhereVerbs =
        listOf(
            "send",
            "schedule",
            "turn on",
            "turn off",
            "set up",
            "setup",
            "check the battery",
            "storage",
            "network state",
            "what time",
            "rename",
            "download",
            "search for",
        )

    /** Only matched in imperative position (message starts with the verb). */
    private val imperativeVerbs =
        listOf(
            "call",
            "make",
            "create",
            "delete",
            "remove",
            "find",
            "adjust",
            "open",
            "move",
            "book",
        )

    private fun wordBoundaryRegex(phrase: String) = Regex("(?i)\\b${Regex.escape(phrase)}\\b")

    fun shouldUseAgent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val afterPrefix = trimmed.getOrNull("jarvis".length)
        if (trimmed.startsWith("jarvis", ignoreCase = true) &&
            (afterPrefix == null || afterPrefix == ',' || afterPrefix.isWhitespace())
        ) {
            return true
        }
        val lower = trimmed.lowercase().removePrefix("please ")
        if (anywhereVerbs.any { wordBoundaryRegex(it).containsMatchIn(lower) }) return true
        return imperativeVerbs.any { lower.startsWith(it + " ") || lower == it }
    }
}
