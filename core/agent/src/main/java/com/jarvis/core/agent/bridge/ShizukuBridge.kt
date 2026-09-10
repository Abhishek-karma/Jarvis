package com.jarvis.core.agent.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader

private const val TAG = "ShizukuBridge"
const val SHIZUKU_PERMISSION_REQUEST_CODE = 8742

/**
 * Pluggable adapter interface isolating raw Shizuku static API calls.
 * Enables 100% deterministic JVM unit testing of status transitions and error handling.
 */
interface ShizukuApiAdapter {
    fun isPackageInstalled(context: Context): Boolean
    fun pingBinder(): Boolean
    fun checkSelfPermission(): Int
    fun shouldShowRequestPermissionRationale(): Boolean
    fun requestPermission(requestCode: Int)
    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener)
    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener)
    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener)
    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener)
    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)
    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)
    fun bindUserService(context: Context, connection: ServiceConnection): Boolean
    fun unbindUserService(context: Context, connection: ServiceConnection)
}

/**
 * Production implementation communicating with official Shizuku API (v13+).
 */
class DefaultShizukuApiAdapter : ShizukuApiAdapter {
    private val packages = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
    )

    override fun isPackageInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return packages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    override fun checkSelfPermission(): Int {
        return try {
            if (!Shizuku.pingBinder()) {
                PackageManager.PERMISSION_DENIED
            } else if (Shizuku.isPreV11()) {
                PackageManager.PERMISSION_DENIED
            } else {
                Shizuku.checkSelfPermission()
            }
        } catch (_: Throwable) {
            PackageManager.PERMISSION_DENIED
        }
    }

    override fun shouldShowRequestPermissionRationale(): Boolean {
        return try {
            Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Throwable) {
            false
        }
    }

    override fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    override fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "Error adding Shizuku binder received listener", e)
        }
    }

    override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (_: Throwable) {}
    }

    override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        try {
            Shizuku.addBinderDeadListener(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "Error adding Shizuku binder dead listener", e)
        }
    }

    override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        try {
            Shizuku.removeBinderDeadListener(listener)
        } catch (_: Throwable) {}
    }

    override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "Error adding Shizuku permission result listener", e)
        }
    }

    override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Throwable) {}
    }

    override fun bindUserService(context: Context, connection: ServiceConnection): Boolean {
        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("service")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind Shizuku UserService", e)
            false
        }
    }

    override fun unbindUserService(context: Context, connection: ServiceConnection) {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuUserService::class.java.name)
            )
            Shizuku.unbindUserService(args, connection, true)
        } catch (_: Throwable) {}
    }
}

/**
 * Production-ready Bridge implementation for Shizuku (ADB-level privileges).
 * Translates TypedOp into policy-governed system commands and privileged binder operations.
 * Communicates with official Shizuku UserService API with graceful fallback to Sandbox.
 */
class ShizukuBridge(
    private val context: Context,
    private val adapter: ShizukuApiAdapter = DefaultShizukuApiAdapter(),
) : PrivilegeBridge {

    override val tier: BridgeTier = BridgeTier.SHIZUKU
    override val name: String = "Shizuku Bridge"

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** Pure, stateless policy engine used for the defense-in-depth shell re-check. */
    private val policyEngine = CommandPolicyEngine()

    private val _statusFlow = MutableStateFlow(BridgeStatus.NOT_INSTALLED)
    val statusFlow: StateFlow<BridgeStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var userService: IShizukuService? = null

    @Volatile
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected: $name")
            userService = IShizukuService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Shizuku UserService disconnected: $name")
            userService = null
            isBound = false
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateStatus()
        tryBindUserService()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        isBound = false
        updateStatus()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        updateStatus()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            tryBindUserService()
        }
    }

    init {
        try {
            adapter.addBinderReceivedListener(binderReceivedListener)
            adapter.addBinderDeadListener(binderDeadListener)
            adapter.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to register Shizuku lifecycle listeners", e)
        }
        updateStatus()
    }

    /**
     * Re-probes the current Shizuku status and updates [statusFlow].
     */
    fun updateStatus(): BridgeStatus {
        val status = computeStatus()
        _statusFlow.value = status
        return status
    }

    private fun computeStatus(): BridgeStatus {
        if (!adapter.isPackageInstalled(context)) {
            return BridgeStatus.NOT_INSTALLED
        }
        if (!adapter.pingBinder()) {
            return BridgeStatus.SERVICE_STOPPED
        }
        if (adapter.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return BridgeStatus.PERMISSION_DENIED
        }
        return BridgeStatus.AVAILABLE
    }

    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE) {
        adapter.requestPermission(requestCode)
    }

    private fun tryBindUserService() {
        if (!isBound && computeStatus() == BridgeStatus.AVAILABLE) {
            adapter.bindUserService(context, serviceConnection)
        }
    }

    override suspend fun isAvailable(): BridgeStatus = withContext(Dispatchers.IO) {
        val status = updateStatus()
        if (status == BridgeStatus.AVAILABLE && !isBound) {
            tryBindUserService()
        }
        status
    }

    override suspend fun execTyped(op: TypedOp): OpResult = withContext(Dispatchers.IO) {
        // 0. Defense in depth: never execute a shell command that fails policy.
        //    (BridgeCoordinator also checks this; re-checking here closes any path
        //    that reaches ShizukuBridge directly without a coordinator pass.)
        if (op is TypedOp.ShellCommand) {
            val eval = policyEngine.evaluate(op.command)
            if (eval.classification == PolicyClassification.BLOCKED) {
                return@withContext OpResult.Blocked(
                    reason = "Command blocked by security policy: ${eval.reason}",
                    matchedPolicyRule = eval.matchedRule,
                )
            }
        }

        // 1. Guard the model-influenced components of typed operations before they
        //    are interpolated into shell strings below.
        TypedOpComponentGuard.validate(op)?.let { reason ->
            return@withContext OpResult.Blocked(
                reason = "Typed operation rejected by security guard: $reason",
                matchedPolicyRule = "TypedOpComponentGuard",
            )
        }

        val status = isAvailable()
        if (!status.isOperable) {
            return@withContext OpResult.Unavailable(
                requiredTier = BridgeTier.SHIZUKU,
                reason = "Shizuku bridge is not available: ${status.message}",
                setupHint = when (status) {
                    BridgeStatus.NOT_INSTALLED -> "Install the Shizuku companion app from GitHub or Play Store."
                    BridgeStatus.SERVICE_STOPPED -> "Start the Shizuku service via Wireless Debugging or ADB."
                    BridgeStatus.PERMISSION_DENIED -> "Grant Shizuku permission to Jarvis in the Shizuku app."
                    else -> "Start Shizuku service and grant permission to Jarvis."
                },
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
            val result = executePrivilegedCommand(shellCommand)

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

    private fun executePrivilegedCommand(command: String): ProcessResult {
        var service = userService
        if (service == null || !isBound) {
            tryBindUserService()
            service = userService
        }

        if (service != null && isBound) {
            try {
                val jsonResponse = service.executeCommand(command)
                val json = JSONObject(jsonResponse)
                return ProcessResult(
                    exitCode = json.optInt("exitCode", -1),
                    stdout = json.optString("stdout", ""),
                    stderr = json.optString("stderr", ""),
                )
            } catch (e: Exception) {
                Log.e(TAG, "UserService execution error", e)
                return ProcessResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "UserService error: ${e.message}",
                )
            }
        }

        return ProcessResult(
            exitCode = -1,
            stdout = "",
            stderr = "Shizuku UserService is not bound. Please grant permission and restart Shizuku.",
        )
    }

    fun release() {
        try {
            adapter.removeBinderReceivedListener(binderReceivedListener)
            adapter.removeBinderDeadListener(binderDeadListener)
            adapter.removeRequestPermissionResultListener(permissionResultListener)
            if (isBound) {
                adapter.unbindUserService(context, serviceConnection)
            }
        } catch (_: Throwable) {}
    }
}
