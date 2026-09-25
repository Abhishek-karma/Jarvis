package com.jarvis.core.agent.needle

/**
 * Raw tool call predicted by Needle 3 with calibrated confidence.
 */
data class NeedleToolCall(
    val name: String,
    val argumentsJson: String,
    val confidence: Float,
)

/**
 * Final high-level routing decision returned by NeedleRouter.
 */
sealed interface RoutingDecision {
    /**
     * Needle resolved a single native capability with high confidence.
     */
    data class Direct(
        val toolName: String,
        val argsJson: String,
        val confidence: Float,
        val userFacingAction: String,
    ) : RoutingDecision

    /**
     * Request must be escalated to AgentRunner / Main LLM.
     */
    data class Escalate(
        val reason: EscalationReason,
        val details: String? = null,
    ) : RoutingDecision
}

enum class EscalationReason {
    LOW_CONFIDENCE,
    COMPLEX_OR_MULTI_STEP,
    UNSUPPORTED,
    MALFORMED_RESULT,
    EMPTY_PROMPT,
}
