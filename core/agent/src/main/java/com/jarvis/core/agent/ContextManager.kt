package com.jarvis.core.agent

import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole

/**
 * Breakdown of token budgets for a prompt request.
 */
data class ContextBudget(
    val maxTotalTokens: Int,
    val systemPromptBudget: Int,
    val toolsBudget: Int,
    val memoryBudget: Int,
    val attachmentsBudget: Int,
    val historyBudget: Int,
) {
    companion object {
        fun create(maxTotalTokens: Int = 4096): ContextBudget {
            val safeTotal = maxOf(maxTotalTokens, 1024)
            val system = (safeTotal * 0.15).toInt()
            val tools = (safeTotal * 0.15).toInt()
            val memory = (safeTotal * 0.10).toInt()
            val attachments = (safeTotal * 0.15).toInt()
            val history = safeTotal - system - tools - memory - attachments
            return ContextBudget(
                maxTotalTokens = safeTotal,
                systemPromptBudget = system,
                toolsBudget = tools,
                memoryBudget = memory,
                attachmentsBudget = attachments,
                historyBudget = history,
            )
        }
    }
}

/**
 * Result of context budgeting and compaction.
 */
data class CompactedContext(
    val messages: List<Message>,
    val estimatedTotalTokens: Int,
    val wasCompacted: Boolean,
    val droppedMessagesCount: Int,
)

/**
 * Manages context budgeting, message history trimming, and summarization checkpoints
 * so that large conversations compact gracefully rather than failing with provider errors.
 */
class ContextManager {

    /**
     * Fast heuristic token estimation (~4 characters per token).
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + 3) / 4
    }

    /**
     * Estimate tokens for a list of messages.
     */
    fun estimateMessagesTokens(messages: List<Message>): Int {
        return messages.sumOf { estimateTokens(it.content) + 4 } // 4 token overhead per turn
    }

    /**
     * Compacts conversation history to strictly fit within the given [historyTokenBudget].
     *
     * Strategy:
     * 1. Preserves the first user turn if available (keeps original user goal/intent).
     * 2. Preserves the most recent messages working backwards from latest.
     * 3. Inserts a compact summarization checkpoint if older turns were pruned.
     */
    fun compactHistory(
        messages: List<Message>,
        historyTokenBudget: Int,
    ): CompactedContext {
        if (messages.isEmpty()) {
            return CompactedContext(emptyList(), 0, false, 0)
        }

        val totalEstimated = estimateMessagesTokens(messages)
        if (totalEstimated <= historyTokenBudget) {
            return CompactedContext(messages, totalEstimated, false, 0)
        }

        val firstMessage = messages.firstOrNull()
        val firstTokenCost = firstMessage?.let { estimateTokens(it.content) + 4 } ?: 0

        val remainingBudget = historyTokenBudget - firstTokenCost - 20 // 20 tokens reserved for checkpoint notice
        val recentMessages = mutableListOf<Message>()
        var accumulatedTokens = 0

        // Collect latest messages backwards
        for (i in (messages.size - 1) downTo 1) {
            val msg = messages[i]
            val cost = estimateTokens(msg.content) + 4
            if (accumulatedTokens + cost > remainingBudget) {
                break
            }
            recentMessages.add(0, msg)
            accumulatedTokens += cost
        }

        val droppedCount = messages.size - (if (firstMessage != null) 1 else 0) - recentMessages.size

        val result = mutableListOf<Message>()
        if (firstMessage != null) {
            result.add(firstMessage)
        }

        if (droppedCount > 0) {
            val checkpoint = Message(
                id = "checkpoint-${System.currentTimeMillis()}",
                conversationId = messages.first().conversationId,
                role = MessageRole.SYSTEM,
                content = "[Context budget enforced: $droppedCount earlier messages compacted for memory limit]",
                createdAt = System.currentTimeMillis(),
            )
            result.add(checkpoint)
        }

        result.addAll(recentMessages)

        val finalTokens = estimateMessagesTokens(result)
        return CompactedContext(
            messages = result,
            estimatedTotalTokens = finalTokens,
            wasCompacted = droppedCount > 0,
            droppedMessagesCount = droppedCount,
        )
    }

    /**
     * Truncates text cleanly if it exceeds the specified token budget.
     */
    fun clampTextToBudget(text: String, tokenBudget: Int): String {
        val estimated = estimateTokens(text)
        if (estimated <= tokenBudget) return text
        val maxChars = tokenBudget * 4
        return text.take(maxChars) + "… [truncated to fit context budget]"
    }
}
