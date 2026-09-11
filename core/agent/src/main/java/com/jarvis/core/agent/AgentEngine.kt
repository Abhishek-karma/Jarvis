package com.jarvis.core.agent

import kotlinx.coroutines.flow.Flow

/**
 * Backwards-compatible facade over [AgentRunner].
 * Delegates execution directly to the canonical v2 [AgentRunner].
 */
class AgentEngine(
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val confirmationGate: ConfirmationGate,
    private val stepCap: Int = DEFAULT_STEP_CAP,
    private val forceConfirm: Boolean = false,
    private val parallelReadLimit: Int = DEFAULT_PARALLEL_READ_LIMIT,
    private val disabledTools: Set<String> = emptySet(),
    private val toolPolicy: ToolPolicy = DefaultToolPolicy(disabledTools),
    private val contextManager: ContextManager = ContextManager(),
) {
    private val runner = AgentRunner(
        registry = registry,
        audit = audit,
        confirmationGate = confirmationGate,
        toolPolicy = toolPolicy,
        stepCap = stepCap,
        forceConfirm = forceConfirm,
        parallelReadLimit = parallelReadLimit,
        disabledTools = disabledTools,
        contextManager = contextManager,
    )

    fun run(request: AgentRunRequest): Flow<AgentEvent> = runner.run(request)

    companion object {
        const val DEFAULT_STEP_CAP = AgentRunner.DEFAULT_STEP_CAP
        const val MAX_STEP_CAP = AgentRunner.MAX_STEP_CAP
        const val DEFAULT_PARALLEL_READ_LIMIT = AgentRunner.DEFAULT_PARALLEL_READ_LIMIT
        val SYSTEM_PROMPT = AgentRunner.SYSTEM_PROMPT
    }
}
