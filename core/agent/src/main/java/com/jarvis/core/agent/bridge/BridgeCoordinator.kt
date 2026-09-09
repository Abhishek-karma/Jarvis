package com.jarvis.core.agent.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeTierInfo(
    val tier: BridgeTier,
    val name: String,
    val status: BridgeStatus,
    val isDefault: Boolean = false,
)

data class BridgeSystemState(
    val activeTier: BridgeTier,
    val tiers: List<BridgeTierInfo>,
    val isExpertModeEnabled: Boolean,
)

/**
 * Coordinator for resolving the lowest available privilege tier required for a given TypedOp.
 * Provides transparent, honest degradation observations when operations exceed active bridge privileges.
 */
class BridgeCoordinator(
    private val sandboxBridge: SandboxBridge,
    private val shizukuBridge: ShizukuBridge,
    private val policyEngine: CommandPolicyEngine,
) {
    private val _systemState = MutableStateFlow(
        BridgeSystemState(
            activeTier = BridgeTier.SANDBOX,
            tiers = listOf(
                BridgeTierInfo(BridgeTier.SANDBOX, "Android Sandbox", BridgeStatus.AVAILABLE, isDefault = true),
                BridgeTierInfo(BridgeTier.SHIZUKU, "Shizuku Bridge", BridgeStatus.NOT_INSTALLED),
                BridgeTierInfo(BridgeTier.ROOT, "Root (Superuser)", BridgeStatus.UNSUPPORTED),
            ),
            isExpertModeEnabled = false,
        )
    )
    val systemState: StateFlow<BridgeSystemState> = _systemState.asStateFlow()

    /**
     * Probes all registered bridges to update their availability status.
     */
    suspend fun probeBridges(isExpertMode: Boolean = false): BridgeSystemState {
        val sandboxStatus = sandboxBridge.isAvailable()
        val shizukuStatus = shizukuBridge.isAvailable()

        val activeTier = if (shizukuStatus.isOperable) {
            BridgeTier.SHIZUKU
        } else {
            BridgeTier.SANDBOX
        }

        val tiers = listOf(
            BridgeTierInfo(BridgeTier.SANDBOX, sandboxBridge.name, sandboxStatus, isDefault = activeTier == BridgeTier.SANDBOX),
            BridgeTierInfo(BridgeTier.SHIZUKU, shizukuBridge.name, shizukuStatus, isDefault = activeTier == BridgeTier.SHIZUKU),
            BridgeTierInfo(BridgeTier.ROOT, "Root Bridge", BridgeStatus.UNSUPPORTED),
        )

        val newState = BridgeSystemState(
            activeTier = activeTier,
            tiers = tiers,
            isExpertModeEnabled = isExpertMode,
        )
        _systemState.value = newState
        return newState
    }

    fun requestShizukuPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE) {
        shizukuBridge.requestPermission(requestCode)
    }

    /**
     * Executes a typed operation via the cheapest live tier capable of fulfilling it.
     */
    suspend fun execute(op: TypedOp): OpResult {
        // 1. If shell command, run safety evaluation through policy engine
        if (op is TypedOp.ShellCommand) {
            val eval = policyEngine.evaluate(op.command)
            if (eval.classification == PolicyClassification.BLOCKED) {
                return OpResult.Blocked(
                    reason = "Command blocked by security policy: ${eval.reason}",
                    matchedPolicyRule = eval.matchedRule,
                )
            }
        }

        // 2. Resolve target bridge
        val requiredTier = op.minimumTier
        val shizukuStatus = shizukuBridge.isAvailable()

        return if (requiredTier == BridgeTier.SHIZUKU) {
            if (shizukuStatus.isOperable) {
                shizukuBridge.execTyped(op)
            } else {
                // Return clear actionable unavailable status with setup hint
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Shizuku bridge is inoperable: ${shizukuStatus.message}",
                    setupHint = when (shizukuStatus) {
                        BridgeStatus.NOT_INSTALLED -> "Install the Shizuku companion app from GitHub or Play Store."
                        BridgeStatus.SERVICE_STOPPED -> "Start the Shizuku service via Wireless Debugging or ADB."
                        BridgeStatus.PERMISSION_DENIED -> "Grant Shizuku permission to Jarvis in the Shizuku app."
                        else -> "Shizuku is required for elevated ADB operations."
                    },
                )
            }
        } else {
            sandboxBridge.execTyped(op)
        }
    }
}
