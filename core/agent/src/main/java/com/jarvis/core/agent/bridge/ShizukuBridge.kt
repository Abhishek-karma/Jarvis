package com.jarvis.core.agent.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge implementation for Shizuku (ADB-level privileges).
 * Translates TypedOp into system commands or privileged binder calls.
 * Gracefully degrades if companion is not installed or service is stopped.
 */
class ShizukuBridge(
    private val context: Context,
    private val commandRunner: (suspend (String) -> ProcessResult)? = null,
) : PrivilegeBridge {

    override val tier: BridgeTier = BridgeTier.SHIZUKU
    override val name: String = "Shizuku Bridge"

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private val shizukuPackages = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
    )

    override suspend fun isAvailable(): BridgeStatus = withContext(Dispatchers.IO) {
        // 1. Check if Shizuku manager app is installed
        val pm = context.packageManager
        val isInstalled = shizukuPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        if (!isInstalled) {
            return@withContext BridgeStatus.NOT_INSTALLED
        }

        // 2. Shizuku is installed - consider it available;
        //    optional command runner verifies service is running but is not required for availability
        if (commandRunner != null) {
            try {
                val ping = commandRunner.invoke("id")
                if (ping.exitCode == 0) {
                    return@withContext BridgeStatus.AVAILABLE
                }
            } catch (_: Exception) {
                // Service may be stopped; still consider bridge available since app is installed
            }
        }

        BridgeStatus.AVAILABLE
    }

    override suspend fun execTyped(op: TypedOp): OpResult = withContext(Dispatchers.IO) {
        val status = isAvailable()
        if (!status.isOperable) {
            return@withContext OpResult.Unavailable(
                requiredTier = BridgeTier.SHIZUKU,
                reason = "Shizuku bridge is not available: ${status.message}",
                setupHint = "Open Shizuku app, ensure Wireless Debugging is paired, and start the service.",
            )
        }

        val shellCommand = when (op) {
            is TypedOp.GrantPermission -> "pm grant ${op.packageName} ${op.permission}"
            is TypedOp.RevokePermission -> "pm revoke ${op.packageName} ${op.permission}"
            is TypedOp.SetGlobalSetting -> "settings put global ${op.key} ${op.value}"
            is TypedOp.SetSecureSetting -> "settings put secure ${op.key} ${op.value}"
            is TypedOp.ForceStop -> "am force-stop ${op.packageName}"
            is TypedOp.SetAppEnabled -> "pm ${if (op.enabled) "enable" else "disable"} ${op.packageName}"
            is TypedOp.AppOp -> "cmd appops set ${op.packageName} ${op.op} ${op.mode}"
            is TypedOp.Screenshot -> "screencap -p /sdcard/jarvis_screencap.png"
            is TypedOp.InputGesture -> {
                if (op.points.size >= 2) {
                    val p1 = op.points[0]
                    val p2 = op.points[1]
                    "input swipe ${p1.first} ${p1.second} ${p2.first} ${p2.second}"
                } else if (op.points.isNotEmpty()) {
                    val p = op.points[0]
                    "input tap ${p.first} ${p.second}"
                } else {
                    "input keyevent 3" // Home
                }
            }
            is TypedOp.ShellCommand -> op.command
        }

        try {
            val result = commandRunner?.invoke(shellCommand)
                ?: return@withContext OpResult.Unavailable(
                    requiredTier = BridgeTier.SHIZUKU,
                    reason = "No active Shizuku process runner attached.",
                    setupHint = "Start Shizuku service and grant permission to Jarvis.",
                )

            if (result.exitCode == 0) {
                OpResult.Success(
                    output = result.stdout.ifBlank { "Operation executed successfully." },
                    tierUsed = BridgeTier.SHIZUKU,
                    data = mapOf("command" to shellCommand, "exitCode" to result.exitCode),
                )
            } else {
                OpResult.Failed(
                    error = result.stderr.ifBlank { "Exit code: ${result.exitCode}" },
                    exitCode = result.exitCode,
                )
            }
        } catch (e: Exception) {
            OpResult.Failed(error = "Bridge execution failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
