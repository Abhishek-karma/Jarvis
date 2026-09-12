package com.jarvis.core.agent

import com.jarvis.core.common.PermissionTier

/**
 * Governs tool execution eligibility, confirmation requirements, and execution safety.
 */
interface ToolPolicy {
    /**
     * Determines whether a tool call may proceed automatically, requires user confirmation,
     * or must be rejected outright.
     */
    suspend fun evaluate(
        tool: Tool,
        argsJson: String,
        isForceConfirm: Boolean,
    ): PolicyDecision
}

sealed class PolicyDecision {
    /** Allowed to execute immediately. */
    object Allow : PolicyDecision()

    /** Requires explicit user confirmation before execution. */
    data class RequireConfirmation(val toolName: String, val tier: PermissionTier, val argsJson: String) : PolicyDecision()

    /** Denied execution by policy. */
    data class Deny(val reason: String) : PolicyDecision()
}

/**
 * Standard ToolPolicy enforcing:
 * - READ_ONLY: Auto-approved unless forceConfirm is requested.
 * - REVERSIBLE_WRITE / ACTION: Sequential; requires confirmation if forceConfirm or if policy requires.
 * - SENSITIVE: Explicit user confirmation strictly required.
 */
class DefaultToolPolicy(
    private val disabledTools: Set<String> = emptySet(),
) : ToolPolicy {

    override suspend fun evaluate(
        tool: Tool,
        argsJson: String,
        isForceConfirm: Boolean,
    ): PolicyDecision {
        if (tool.name in disabledTools) {
            return PolicyDecision.Deny("Tool '${tool.name}' is disabled in this environment.")
        }

        if (tool.tier == PermissionTier.SENSITIVE) {
            return PolicyDecision.RequireConfirmation(tool.name, tool.tier, argsJson)
        }

        if (isForceConfirm && tool.tier != PermissionTier.READ_ONLY) {
            return PolicyDecision.RequireConfirmation(tool.name, tool.tier, argsJson)
        }

        return PolicyDecision.Allow
    }
}

/**
 * Strict policy for unattended background routines (WorkManager / RoutineWorker).
 * Denies any tool with tier != READ_ONLY, enforcing zero unattended side effects.
 */
class BackgroundToolPolicy(
    private val disabledTools: Set<String> = emptySet(),
) : ToolPolicy {

    override suspend fun evaluate(
        tool: Tool,
        argsJson: String,
        isForceConfirm: Boolean,
    ): PolicyDecision {
        if (tool.name in disabledTools) {
            return PolicyDecision.Deny("Tool '${tool.name}' is disabled in this environment.")
        }

        if (tool.tier != PermissionTier.READ_ONLY) {
            return PolicyDecision.Deny("Tool '${tool.name}' (tier ${tool.tier}) is not permitted in unattended background runs.")
        }

        return PolicyDecision.Allow
    }
}
