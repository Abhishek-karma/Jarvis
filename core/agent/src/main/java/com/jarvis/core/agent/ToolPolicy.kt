package com.jarvis.core.agent

import com.jarvis.core.agent.tools.Args
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

    /**
     * Canonical check for whether a concrete UI action (e.g. click/tap) target is consequential/sensitive.
     */
    fun isSensitiveUiAction(action: String, target: String): Boolean = false
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

        if (tool.tier == PermissionTier.SENSITIVE ||
            tool.name == "place_call" ||
            tool.name == "send_sms"
        ) {
            return PolicyDecision.RequireConfirmation(tool.name, tool.tier, argsJson)
        }

        if (tool.name == "ui_click" && isSensitiveUiClick(argsJson)) {
            return PolicyDecision.RequireConfirmation(tool.name, PermissionTier.SENSITIVE, argsJson)
        }

        if (tool.name == "ui_type" && isSensitiveUiType(argsJson)) {
            return PolicyDecision.RequireConfirmation(tool.name, PermissionTier.SENSITIVE, argsJson)
        }

        if (tool.name == "phone_agent" && isSensitivePhoneGoal(argsJson)) {
            return PolicyDecision.RequireConfirmation(tool.name, PermissionTier.SENSITIVE, argsJson)
        }

        if (isForceConfirm && tool.tier != PermissionTier.READ_ONLY) {
            return PolicyDecision.RequireConfirmation(tool.name, tool.tier, argsJson)
        }

        return PolicyDecision.Allow
    }

    override fun isSensitiveUiAction(action: String, target: String): Boolean {
        val cleanTarget = target.lowercase(java.util.Locale.US).trim()
        val sensitiveKeywords = listOf(
            "send", "submit", "post", "tweet", "publish",
            "delete", "remove", "clear", "clear all", "erase", "discard",
            "buy", "purchase", "pay", "order", "place order", "checkout", "transfer",
            "call", "dial", "hang up", "confirm", "approve", "format", "reset", "uninstall",
        )
        return sensitiveKeywords.any { kw ->
            cleanTarget == kw ||
                cleanTarget.startsWith("$kw ") ||
                cleanTarget.endsWith(" $kw") ||
                cleanTarget.contains(" $kw ") ||
                cleanTarget.contains("_$kw") ||
                cleanTarget.contains("${kw}_")
        }
    }

    private fun isSensitiveUiClick(argsJson: String): Boolean {
        val target = Args.parse(argsJson)?.string("target") ?: return false
        return isSensitiveUiAction("click", target)
    }

    private fun isSensitiveUiType(argsJson: String): Boolean {
        val parsed = Args.parse(argsJson) ?: return false
        val submit = parsed.boolean("submit") ?: false
        if (submit) return true
        val target = parsed.string("target")
        if (!target.isNullOrBlank() && isSensitiveUiAction("type", target)) return true
        val text = parsed.string("text")
        if (!text.isNullOrBlank() && isSensitiveUiAction("type", text)) return true
        return false
    }

    private fun isSensitivePhoneGoal(argsJson: String): Boolean {
        val goal = Args.parse(argsJson)?.string("goal")?.lowercase()?.trim() ?: return false
        val sensitiveActions = listOf(
            "send it", "send message", "send email", "send money",
            "delete", "remove", "erase", "format",
            "buy", "purchase", "pay", "order", "place order", "checkout", "transfer",
            "post", "publish", "tweet",
        )
        return sensitiveActions.any { action ->
            goal.contains(action)
        }
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
