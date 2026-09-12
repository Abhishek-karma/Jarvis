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
    val maxObservationTokens: Int,
    val outputReserveTokens: Int,
) {
    companion object {
        fun create(maxTotalTokens: Int = 4096): ContextBudget {
            val safeTotal = maxOf(maxTotalTokens, 1024)
            val output = (safeTotal * 0.15).toInt().coerceIn(256, 1024)
            val system = (safeTotal * 0.15).toInt().coerceIn(200, 1000)
            val tools = (safeTotal * 0.15).toInt().coerceIn(200, 1000)
            val memory = (safeTotal * 0.10).toInt().coerceIn(100, 600)
            val attachments = (safeTotal * 0.10).toInt().coerceIn(150, 800)
            val history = safeTotal - system - tools - memory - attachments - output
            return ContextBudget(
                maxTotalTokens = safeTotal,
                systemPromptBudget = system,
                toolsBudget = tools,
                memoryBudget = memory,
                attachmentsBudget = attachments,
                historyBudget = maxOf(history, 300),
                maxObservationTokens = (safeTotal * 0.25).toInt().coerceIn(300, 1500),
                outputReserveTokens = output,
            )
        }

        fun forLocal(maxTotalTokens: Int = 2048): ContextBudget {
            val total = maxOf(maxTotalTokens, 1024)
            val output = 256
            val system = 250
            val tools = 300
            val memory = 150
            val attachments = 200
            val maxObs = 400
            val history = total - system - tools - memory - attachments - output
            return ContextBudget(
                maxTotalTokens = total,
                systemPromptBudget = system,
                toolsBudget = tools,
                memoryBudget = memory,
                attachmentsBudget = attachments,
                historyBudget = maxOf(history, 400),
                maxObservationTokens = maxObs,
                outputReserveTokens = output,
            )
        }

        fun forCloud(maxTotalTokens: Int = 8192): ContextBudget {
            val total = maxOf(maxTotalTokens, 2048)
            val output = 1024
            val system = (total * 0.15).toInt().coerceIn(600, 1500)
            val tools = (total * 0.15).toInt().coerceIn(600, 1500)
            val memory = (total * 0.08).toInt().coerceIn(300, 800)
            val attachments = (total * 0.12).toInt().coerceIn(400, 1500)
            val maxObs = 1200
            val history = total - system - tools - memory - attachments - output
            return ContextBudget(
                maxTotalTokens = total,
                systemPromptBudget = system,
                toolsBudget = tools,
                memoryBudget = memory,
                attachmentsBudget = attachments,
                historyBudget = maxOf(history, 1500),
                maxObservationTokens = maxObs,
                outputReserveTokens = output,
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
 * Atomic interaction unit preserving LLM-tool message structure:
 * - Standalone user or assistant message
 * - Tool turn (assistant tool call + matching tool result(s))
 */
sealed class AtomicTurn {
    abstract val messages: List<Message>

    data class Single(val message: Message) : AtomicTurn() {
        override val messages: List<Message> = listOf(message)
    }

    data class ToolGroup(override val messages: List<Message>) : AtomicTurn()
}

/**
 * Manages context budgeting, atomic message history trimming, observation clamping,
 * and overflow recovery so agent turns never violate LLM format constraints or context limits.
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
     * Estimate tokens for a single message.
     */
    fun estimateMessageTokens(message: Message): Int {
        var count = estimateTokens(message.content) + 4
        message.toolCallName?.let { count += estimateTokens(it) }
        message.toolCallArgsJson?.let { count += estimateTokens(it) }
        return count
    }

    /**
     * Estimate tokens for a list of messages.
     */
    fun estimateMessagesTokens(messages: List<Message>): Int {
        return messages.sumOf { estimateMessageTokens(it) }
    }

    /**
     * Clamps large tool observations (web results, logs, file content, shell output, JSON).
     * Retains error indicators and head/tail context.
     */
    fun clampObservation(text: String, maxTokens: Int = 1000): String {
        val estimated = estimateTokens(text)
        if (estimated <= maxTokens) return text

        val maxChars = maxTokens * 4
        val isError = text.contains("error", ignoreCase = true) ||
            text.contains("exception", ignoreCase = true) ||
            text.contains("failed", ignoreCase = true) ||
            text.contains("denied", ignoreCase = true)

        val headChars = (maxChars * 0.65).toInt()
        val tailChars = (maxChars * 0.25).toInt()

        val head = text.take(headChars)
        val tail = text.takeLast(tailChars)
        val omitted = text.length - headChars - tailChars

        return buildString {
            if (isError) {
                appendLine("[Note: Output contains tool error/exception warnings]")
            }
            append(head)
            append("\n\n... [Observation clamped: omitted $omitted chars to fit context budget] ...\n\n")
            append(tail)
        }
    }

    /**
     * Groups messages into atomic interaction units to ensure tool calls
     * and their corresponding tool results are never split or orphaned.
     */
    fun groupAtomicTurns(messages: List<Message>): List<AtomicTurn> {
        if (messages.isEmpty()) return emptyList()

        val units = mutableListOf<AtomicTurn>()
        var index = 0

        while (index < messages.size) {
            val msg = messages[index]

            // Assistant message containing tool call(s)
            val hasToolCall = msg.role == MessageRole.ASSISTANT &&
                (!msg.toolCallId.isNullOrBlank() || !msg.toolCallName.isNullOrBlank())

            if (hasToolCall) {
                val group = mutableListOf<Message>()
                group.add(msg)
                index++

                // Consume all immediately following matching TOOL response messages
                while (index < messages.size && messages[index].role == MessageRole.TOOL) {
                    group.add(messages[index])
                    index++
                }

                // If followed immediately by another Assistant tool call in the same step, keep them atomic
                while (index < messages.size && messages[index].role == MessageRole.ASSISTANT &&
                    (!messages[index].toolCallId.isNullOrBlank() || !messages[index].toolCallName.isNullOrBlank())
                ) {
                    group.add(messages[index])
                    index++
                    while (index < messages.size && messages[index].role == MessageRole.TOOL) {
                        group.add(messages[index])
                        index++
                    }
                }

                units.add(AtomicTurn.ToolGroup(group))
            } else {
                units.add(AtomicTurn.Single(msg))
                index++
            }
        }

        return units
    }

    /**
     * Compacts conversation history to strictly fit within the given [historyTokenBudget],
     * strictly preserving message atomicity (tool call + tool result are never separated).
     *
     * Invariants:
     * 1. Preserves the first user turn if present (preserves original goal/intent).
     * 2. Preserves the latest user turn (the current active request).
     * 3. Collects recent atomic turns working backwards from latest.
     * 4. Drops entire atomic turns that do not fit (never keeps a tool call without result or vice versa).
     * 5. Inserts a clean system compaction marker if older turns were pruned.
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

        val atomicTurns = groupAtomicTurns(messages)
        if (atomicTurns.isEmpty()) {
            return CompactedContext(emptyList(), 0, false, 0)
        }

        val firstTurn = atomicTurns.first()
        val lastTurn = atomicTurns.last()

        val firstTurnCost = estimateMessagesTokens(firstTurn.messages)
        val lastTurnCost = if (atomicTurns.size > 1) estimateMessagesTokens(lastTurn.messages) else 0

        val checkpointOverhead = 25 // tokens for system checkpoint marker
        var remainingBudget = historyTokenBudget - firstTurnCost - lastTurnCost - checkpointOverhead

        val selectedMiddleTurns = mutableListOf<AtomicTurn>()
        var droppedCount = 0

        // If we only have 1 or 2 turns, keep what we have
        if (atomicTurns.size <= 2) {
            val allMsgs = atomicTurns.flatMap { it.messages }
            return CompactedContext(
                messages = allMsgs,
                estimatedTotalTokens = estimateMessagesTokens(allMsgs),
                wasCompacted = false,
                droppedMessagesCount = 0,
            )
        }

        // Collect middle turns working backwards from (size - 2) down to 1
        for (i in (atomicTurns.size - 2) downTo 1) {
            val turn = atomicTurns[i]
            val turnCost = estimateMessagesTokens(turn.messages)

            if (turnCost <= remainingBudget) {
                selectedMiddleTurns.add(0, turn)
                remainingBudget -= turnCost
            } else {
                droppedCount += turn.messages.size
            }
        }

        val resultMessages = mutableListOf<Message>()
        resultMessages.addAll(firstTurn.messages)

        if (droppedCount > 0) {
            val checkpoint = Message(
                id = "checkpoint-${System.currentTimeMillis()}",
                conversationId = messages.first().conversationId,
                role = MessageRole.SYSTEM,
                content = "[Context budget enforced: $droppedCount earlier messages compacted for memory limit]",
                createdAt = System.currentTimeMillis(),
            )
            resultMessages.add(checkpoint)
        }

        selectedMiddleTurns.forEach { turn ->
            resultMessages.addAll(turn.messages)
        }

        if (atomicTurns.size > 1) {
            resultMessages.addAll(lastTurn.messages)
        }

        val finalTokens = estimateMessagesTokens(resultMessages)
        return CompactedContext(
            messages = resultMessages,
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

    /**
     * Prepares full context under total budget: clamps observations, compacts history,
     * budgets memory, and guarantees the active user request is never dropped.
     */
    fun prepareContext(
        history: List<Message>,
        memoryContext: String?,
        budget: ContextBudget,
        systemPromptTokens: Int = 0,
        toolsTokens: Int = 0,
    ): CompactedContext {
        // Step 1: Clamp large observations in history
        val clampedHistory = history.map { msg ->
            if (msg.role == MessageRole.TOOL) {
                msg.copy(content = clampObservation(msg.content, budget.maxObservationTokens))
            } else {
                msg
            }
        }

        // Step 2: Calculate effective history budget
        val usedTokens = systemPromptTokens + toolsTokens + estimateTokens(memoryContext.orEmpty())
        val availableForHistory = maxOf(budget.maxTotalTokens - usedTokens - budget.outputReserveTokens, budget.historyBudget)

        // Step 3: Compact history preserving atomic turns
        var compacted = compactHistory(clampedHistory, availableForHistory)

        // Step 4: Overflow fallback if still exceeding
        val totalEstimate = systemPromptTokens + toolsTokens + estimateTokens(memoryContext.orEmpty()) + compacted.estimatedTotalTokens
        if (totalEstimate > budget.maxTotalTokens) {
            // Aggressive fallback: preserve first and last turns only
            val emergencyBudget = maxOf(budget.maxTotalTokens - systemPromptTokens - toolsTokens - budget.outputReserveTokens, 200)
            compacted = compactHistory(clampedHistory, emergencyBudget)
        }

        return compacted
    }
}
