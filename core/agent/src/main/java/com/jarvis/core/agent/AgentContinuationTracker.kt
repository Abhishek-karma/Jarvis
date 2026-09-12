package com.jarvis.core.agent

import com.jarvis.core.common.Message

/**
 * Safe agent-task continuation across turns.
 *
 * When the assistant asks for missing information and the user's next reply is not itself
 * an agent command ("welcome"), this tracker lets the next message in the SAME conversation
 * continue the parked task instead of dying in plain chat. Fires only with real pending
 * state: matching conversation id, not expired, not consumed, no cancel / task-switch.
 */
class AgentContinuationTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    data class PendingContinuation(
        val conversationId: String,
        val toolName: String,
        val missingFields: List<String>,
        val partialArgsJson: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val history: List<Message>,
    ) {
        fun isExpired(now: Long): Boolean = now >= expiresAtMs
    }

    private var pending: PendingContinuation? = null

    /** Parks a task waiting on the user for [missingFields] of [toolName]. */
    fun park(
        conversationId: String,
        toolName: String,
        missingFields: List<String>,
        partialArgsJson: String?,
        history: List<Message>,
    ) {
        if (missingFields.isEmpty()) return
        val now = nowMs()
        pending =
            PendingContinuation(
                conversationId = conversationId,
                toolName = toolName,
                missingFields = missingFields.toList(),
                partialArgsJson = partialArgsJson,
                createdAtMs = now,
                expiresAtMs = now + ttlMs,
                history = history.toList(),
            )
    }

    /**
     * Returns the pending continuation when [userText] should continue it, consuming it in all
     * terminal cases (continue / cancel / task-switch). Null when nothing is pending, it
     * expired, the conversation changed, or the text is unrelated chat.
     */
    fun consumeIfContinuation(conversationId: String, userText: String): PendingContinuation? {
        val current = pending ?: return null
        if (current.conversationId != conversationId || current.isExpired(nowMs())) {
            pending = null
            return null
        }
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return null
        if (isCancel(trimmed) || isNewTask(trimmed)) {
            pending = null
            return null
        }
        pending = null
        return current
    }

    /** Explicit user cancellation of the parked task. */
    fun cancel(conversationId: String) {
        if (pending?.conversationId == conversationId) pending = null
    }

    fun peek(): PendingContinuation? {
        val current = pending ?: return null
        if (current.isExpired(nowMs())) {
            pending = null
            return null
        }
        return current
    }

    private fun isCancel(text: String): Boolean {
        val lower = text.lowercase()
        return lower in CANCEL_PHRASES || (lower.startsWith("cancel") && lower.length < 40)
    }

    private fun isNewTask(text: String): Boolean {
        // A fresh imperative agent command for a DIFFERENT task abandons the parked one.
        if (!AgentTrigger.shouldUseAgent(text)) return false
        val lower = text.lowercase()
        return !mentionsParkedTool(lower, pending?.toolName.orEmpty())
    }

    private fun mentionsParkedTool(lower: String, toolName: String): Boolean =
        when (toolName) {
            "create_file" -> lower.contains("file") || lower.contains("txt")
            "read_file" -> lower.contains("file") || lower.contains("read")
            "search_files" -> lower.contains("file") || lower.contains("search") || lower.contains("find")
            else -> lower.contains(toolName.replace('_', ' '))
        }

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        private val CANCEL_PHRASES = setOf(
            "cancel", "never mind", "nevermind", "forget it", "stop", "abort",
            "don't do it", "dont do it", "no thanks", "no thank you",
        )

        /** Missing required `create_file` fields given partial args + user-supplied values. */
        fun missingCreateFileFields(argsJson: String?, extraValues: List<String>): List<String> {
            val hasName = !extractJsonString(argsJson, "file_name").isNullOrBlank() ||
                extraValues.any { looksLikeFileName(it) }
            val hasContent = !extractJsonString(argsJson, "content").isNullOrBlank() ||
                extraValues.any { it.isNotBlank() }
            return buildList {
                if (!hasName) add("file_name")
                if (!hasContent) add("content")
            }
        }

        /** Folds user free text into create_file args (filename vs content heuristic). */
        fun fillCreateFileArgs(partialArgsJson: String?, userText: String): String {
            val trimmed = userText.trim()
            val existingName = extractJsonString(partialArgsJson, "file_name")
            val existingContent = extractJsonString(partialArgsJson, "content")
            val name = existingName?.takeIf { it.isNotBlank() }
                ?: guessFileName(trimmed)
                ?: "note.txt"
            val content = existingContent?.takeIf { it.isNotBlank() } ?: trimmed
            return "{\"file_name\":" + jsonQuote(name) + ",\"content\":" + jsonQuote(content) + "}"
        }

        private fun looksLikeFileName(value: String): Boolean {
            val t = value.trim()
            if (t.isEmpty() || t.length > 64 || t.contains("\n")) return false
            if (t.contains("..") || t.contains("/") || t.contains("\\")) return false
            return t.contains(".") || !t.contains(" ")
        }

        private fun guessFileName(trimmed: String): String? {
            val first = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
            if (first.isEmpty() || first.length > 64) return null
            if (first.contains("..") || first.contains("/") || first.contains("\\")) return null
            if (looksLikeFileName(first)) {
                return if (first.contains(".")) first else "$first.txt"
            }
            return null
        }

        private fun extractJsonString(argsJson: String?, key: String): String? {
            if (argsJson.isNullOrBlank()) return null
            val keyIdx = argsJson.indexOf("\"$key\"")
            if (keyIdx == -1) return null
            val colon = argsJson.indexOf(':', keyIdx)
            if (colon == -1) return null
            val open = argsJson.indexOf('"', colon)
            if (open == -1) return null
            val sb = StringBuilder()
            var j = open + 1
            while (j < argsJson.length) {
                val c = argsJson[j]
                if (c == '\\' && j + 1 < argsJson.length) {
                    sb.append(argsJson[j + 1])
                    j += 2
                } else if (c == '"') {
                    return sb.toString()
                } else {
                    sb.append(c)
                    j++
                }
            }
            return null
        }

        private fun jsonQuote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }
}
