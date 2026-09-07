package com.jarvis.core.agent.bridge

import android.content.Context

/**
 * Standard unprivileged Android sandbox bridge.
 * Executes unprivileged actions and yields honest fallback observations when elevated control is required.
 */
class SandboxBridge(
    private val context: Context,
) : PrivilegeBridge {
    override val tier: BridgeTier = BridgeTier.SANDBOX
    override val name: String = "Android Sandbox"

    override suspend fun isAvailable(): BridgeStatus = BridgeStatus.AVAILABLE

    override suspend fun execTyped(op: TypedOp): OpResult {
        return when (op) {
            is TypedOp.ShellCommand -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Shell execution is not permitted within standard application sandbox.",
                    setupHint = "Enable Shizuku bridge or connect ADB to execute shell commands.",
                )
            }
            is TypedOp.GrantPermission -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Silent permission grants require elevated ADB privileges.",
                    setupHint = "Grant permission via Android Settings -> Apps -> ${op.packageName}, or connect Shizuku.",
                )
            }
            is TypedOp.RevokePermission -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Silent permission revocation requires elevated ADB privileges.",
                    setupHint = "Revoke permission via Android Settings -> Apps -> ${op.packageName}, or connect Shizuku.",
                )
            }
            is TypedOp.SetGlobalSetting -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Modifying Global Settings ('${op.key}') is restricted by Android OS for third-party apps.",
                    setupHint = "Adjust settings manually in Android Settings, or authorize via Shizuku.",
                )
            }
            is TypedOp.SetSecureSetting -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Modifying Secure Settings ('${op.key}') requires WRITE_SECURE_SETTINGS or Shizuku bridge.",
                    setupHint = "Authorize WRITE_SECURE_SETTINGS via ADB or Shizuku bridge.",
                )
            }
            is TypedOp.ForceStop -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Force-stopping '${op.packageName}' requires elevated privileges.",
                    setupHint = "Force-stop via Android Settings -> Apps -> ${op.packageName} -> Force Stop, or use Shizuku.",
                )
            }
            is TypedOp.SetAppEnabled -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Enabling/disabling apps requires elevated privileges.",
                    setupHint = "Toggle in Settings -> Apps -> ${op.packageName}, or connect Shizuku.",
                )
            }
            is TypedOp.AppOp -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Modifying AppOps requires elevated privileges.",
                    setupHint = "Configure AppOps via Shizuku bridge.",
                )
            }
            is TypedOp.Screenshot -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "Programmatic screencap across arbitrary apps requires Shizuku or MediaProjection.",
                    setupHint = "Use hardware keys (Power + Vol Down) or connect Shizuku bridge.",
                )
            }
            is TypedOp.InputGesture -> {
                OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "System-wide input gesture injection requires Shizuku or Accessibility.",
                    setupHint = "Connect Shizuku bridge for input gestures.",
                )
            }
        }
    }
}
