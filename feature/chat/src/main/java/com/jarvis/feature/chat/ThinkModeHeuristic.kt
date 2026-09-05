package com.jarvis.feature.chat

import com.jarvis.core.common.ThinkMode

/**
 * Resolves the user's ThinkMode (persisted preference) into the concrete wire-level
 * `reasoningRequested` flag for one message. Pure and dependency-free — fully unit-testable.
 *
 * AUTO heuristic (v0.5 spec, `03-FEATURES §2` / `06-AGENT §1`): think when the message is
 * math/code, a creative-writing prompt, or an explicit ask for step-by-step reasoning;
 * otherwise don't. Explicit OFF/ON short-circuit.
 */
object ThinkModeHeuristic {
    fun shouldThink(
        message: String,
        mode: ThinkMode,
    ): Boolean =
        when (mode) {
            ThinkMode.OFF -> false
            ThinkMode.ON -> true
            ThinkMode.AUTO -> isThinkingWorthy(message)
        }

    private val codePatterns =
        listOf(
            // A fenced code block anywhere in the message.
            Regex("```", RegexOption.IGNORE_CASE),
            // Math: equations, integrals, sums, or a function definition.
            Regex("[∫Σ∑]"),
            Regex("\\b\\w+\\s*=\\s*[^=\\s].*"), // x = ... (assignment/equation, not ==)
            Regex("\\b(def|function|class|impl|fn)\\s+\\w+", RegexOption.IGNORE_CASE),
        )

    private val creativePatterns =
        listOf(
            Regex("\\b(write|compose|draft|make) (?:me )?(?:a |an |the )?(short |long )?(poem|song|story|haiku|limerick|sonnet|ballad|rhyme|screenplay|script)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(essay|blog post|sonnet|haiku)\\b", RegexOption.IGNORE_CASE),
        )

    private val reasoningAskPatterns =
        listOf(
            Regex("\\b(step[ -]by[ -]step|explain how you|show your work|reason through|think through|work through)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(solve|prove|derive|calculate|compute)\\b", RegexOption.IGNORE_CASE),
            // Math vocabulary without an explicit solve-verb: "integral of x^2", "derivative of sin".
            Regex("\\b(integral|derivative|limit|matrix|eigenvalue|polynomial|equation)\\b", RegexOption.IGNORE_CASE),
        )

    private fun isThinkingWorthy(message: String): Boolean =
        matchesAny(message, codePatterns) ||
            matchesAny(message, creativePatterns) ||
            matchesAny(message, reasoningAskPatterns)

    private fun matchesAny(
        message: String,
        patterns: List<Regex>,
    ): Boolean = patterns.any { it.containsMatchIn(message) }
}
