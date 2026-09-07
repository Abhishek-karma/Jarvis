package com.jarvis.core.agent.bridge

/**
 * Privilege tier hierarchy for device control.
 * SANDBOX: Standard unprivileged Android sandbox.
 * SHIZUKU: Elevated ADB-level privileges via user-approved Shizuku bridge.
 * ROOT: Direct root access (if available).
 */
enum class BridgeTier(val displayName: String, val level: Int) {
    SANDBOX("Sandbox (Standard)", 0),
    SHIZUKU("Shizuku (Elevated ADB)", 1),
    ROOT("Root (Superuser)", 2),
}

/**
 * Runtime availability status of a privilege bridge.
 */
enum class BridgeStatus(val isOperable: Boolean, val message: String) {
    AVAILABLE(true, "Connected & authorized"),
    NOT_INSTALLED(false, "Bridge companion app not installed"),
    SERVICE_STOPPED(false, "Bridge service is stopped — please start companion"),
    PERMISSION_DENIED(false, "Bridge permission not granted"),
    UNSUPPORTED(false, "Bridge is not supported on this device/environment"),
}

/**
 * Strongly typed operation requested by the agent.
 * Raw strings never cross the SPI directly; shell translation occurs internally per bridge.
 */
sealed interface TypedOp {
    data class GrantPermission(val packageName: String, val permission: String) : TypedOp
    data class RevokePermission(val packageName: String, val permission: String) : TypedOp
    data class SetGlobalSetting(val key: String, val value: String) : TypedOp
    data class SetSecureSetting(val key: String, val value: String) : TypedOp
    data class ForceStop(val packageName: String) : TypedOp
    data class SetAppEnabled(val packageName: String, val enabled: Boolean) : TypedOp
    data class AppOp(val mode: String, val packageName: String, val op: String) : TypedOp
    data object Screenshot : TypedOp
    data class InputGesture(val type: String, val points: List<Pair<Float, Float>> = emptyList()) : TypedOp
    data class ShellCommand(val command: String) : TypedOp

    /** Minimum required privilege tier for this typed operation. */
    val minimumTier: BridgeTier
        get() = when (this) {
            is GrantPermission,
            is RevokePermission,
            is SetGlobalSetting,
            is SetSecureSetting,
            is ForceStop,
            is SetAppEnabled,
            is AppOp,
            is Screenshot,
            is InputGesture,
            is ShellCommand -> BridgeTier.SHIZUKU
        }

    /** Whether this operation is read-only (safe for background automated runs). */
    val isReadOnly: Boolean
        get() = when (this) {
            is Screenshot -> true
            is ShellCommand -> command.trim().startsWith("dumpsys") ||
                    command.trim().startsWith("getprop") ||
                    command.trim().startsWith("pm list") ||
                    command.trim().startsWith("settings get")
            else -> false
        }
}

/**
 * Result of executing a typed operation on a privilege bridge.
 */
sealed interface OpResult {
    data class Success(
        val output: String,
        val tierUsed: BridgeTier,
        val data: Map<String, Any> = emptyMap(),
    ) : OpResult

    data class Unavailable(
        val requiredTier: BridgeTier,
        val reason: String,
        val setupHint: String,
    ) : OpResult

    data class Blocked(
        val reason: String,
        val matchedPolicyRule: String? = null,
    ) : OpResult

    data class Failed(
        val error: String,
        val exitCode: Int? = null,
    ) : OpResult
}

/**
 * Service Provider Interface (SPI) for privileged device bridges.
 */
interface PrivilegeBridge {
    val tier: BridgeTier
    val name: String

    suspend fun isAvailable(): BridgeStatus

    suspend fun execTyped(op: TypedOp): OpResult
}
