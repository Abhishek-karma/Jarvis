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
            "battery level",
            "battery status",
            "battery percentage",
            "power level",
            "storage",
            "free storage",
            "disk space",
            "free space",
            "ram",
            "memory status",
            "device info",
            "system info",
            "network state",
            "what time",
            "what is the time",
            "what's the time",
            "tell me the time",
            "current time",
            "what date",
            "what is the date",
            "what's the date",
            "what date is it",
            "tell me the date",
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
            "delete file",
            "list files",
            "text file",
            "txt file",
            "search web",
            "search online",
            "web search",
            "look up",
            "google",
            "clipboard",
            "copy to clipboard",
            "read clipboard",
            "paste clipboard",
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
            "open camera",
            "launch camera",
            "opencamera",
            "open settings",
            "open gallery",
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
            "fetch url",
            "calculate",
            "evaluate",
            "compute",
            "math",
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
            "calc",
            "calculate",
            "eval",
            "evaluate",
            "compute",
            "solve",
            "sum",
            "add",
            "multiply",
            "divide",
            "undo",
            "revert",
            "update",
            "list",
            "toggle",
            "write",
            "browse",
            "get",
        )

    private val mathRegex = Regex("""(?i)\b\d+\s*[\+\-\*\/\^]\s*\d+\b""")

    private val ambiguousVerbs = setOf("call", "make", "find")

    private val conversationalActionPrefixes = listOf(
        "i need you to ",
        "i want you to ",
        "help me ",
        "assist me with ",
        "hey jarvis ",
        "ok jarvis ",
    )

    private val modalQuestionPrefixes = listOf(
        "can you ",
        "could you ",
        "would you ",
        "will you ",
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
            (afterPrefix == null || afterPrefix == ',' || afterPrefix == ':' || afterPrefix.isWhitespace())
        ) {
            return true
        }
        var lower = trimmed.lowercase().removePrefix("please ")

        var strippedModalQuestion = false
        for (prefix in modalQuestionPrefixes) {
            if (lower.startsWith(prefix)) {
                lower = lower.removePrefix(prefix).trimStart().removePrefix("please ")
                strippedModalQuestion = true
                break
            }
        }
        if (!strippedModalQuestion) {
            for (prefix in conversationalActionPrefixes) {
                if (lower.startsWith(prefix)) {
                    lower = lower.removePrefix(prefix).trimStart().removePrefix("please ")
                    break
                }
            }
        }

        if (mathRegex.containsMatchIn(lower)) return true
        if (anywherePatterns.any { it.containsMatchIn(lower) }) return true

        return imperativeVerbs.any { verb ->
            if (strippedModalQuestion && verb in ambiguousVerbs) {
                // If it was a question like "can you make sense of this?", only match if it contains a clear tool phrase
                false
            } else {
                lower.startsWith(verb + " ") || lower == verb
            }
        }
    }
}

