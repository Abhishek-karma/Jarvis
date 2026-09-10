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
            "battery",
            "storage",
            "network state",
            "what time",
            "current time",
            "what date",
            "what is the date",
            "what's the date",
            "today's date",
            "todays date",
            "current date",
            "rename",
            "download",
            "search for",
            "search files",
            "find file",
            "read file",
            "write file",
            "create file",
            "save file",
            "search web",
            "google",
            "clipboard",
            "copy to clipboard",
            "read clipboard",
            "timer",
            "set timer",
            "alarm",
            "set alarm",
            "reminder",
            "remind me",
            "calendar",
            "take photo",
            "take picture",
            "take a photo",
            "take a picture",
            "record audio",
            "volume",
            "launch app",
            "open app",
            "notification",
            "notify me",
            "save memory",
            "remember that",
            "remember this",
            "remember my",
            "remember i",
            "don't forget",
            "what do you remember",
            "what do you know about me",
            "what is my name",
            "what's my name",
            "who am i",
            "my preferences",
            "recall memory",
            "recall memories",
            "show memories",
            "list memories",
            "forget that",
            "forget my",
            "clear memory",
            "delete memory",
            "undo that",
            "undo action",
            "revert that",
            "revert action",
            "create task",
            "list tasks",
            "fetch webpage",
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
            "launch",
            "start",
            "run",
            "move",
            "book",
            "set",
            "copy",
            "paste",
            "read",
            "show",
            "notify",
            "check",
            "search",
            "fetch",
            "take",
            "record",
            "remember",
            "forget",
            "recall",
            "calculate",
            "compute",
            "solve",
            "undo",
            "revert",
            "update",
            "list",
            "toggle",
            "write",
            "browse",
        )

    private val anywherePatterns: List<Regex> =
        anywhereVerbs.map { phrase ->
            Regex("(?i)\\b${Regex.escape(phrase)}\\b")
        }

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
        if (anywherePatterns.any { it.containsMatchIn(lower) }) return true
        return imperativeVerbs.any { lower.startsWith(it + " ") || lower == it }
    }
}
