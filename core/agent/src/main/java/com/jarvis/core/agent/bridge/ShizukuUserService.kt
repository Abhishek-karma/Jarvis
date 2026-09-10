package com.jarvis.core.agent.bridge

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

private const val TAG = "ShizukuUserService"

/**
 * Privileged service instantiated and executed by Shizuku in a separate process
 * with elevated ADB (UID 2000) or Root (UID 0) privileges.
 */
class ShizukuUserService : IShizukuService.Stub {

    constructor() : super()

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        Log.i(TAG, "destroy() invoked by Shizuku host; exiting process")
        exit()
    }

    override fun exit() {
        try {
            exitProcess(0)
        } catch (_: Throwable) {
            System.exit(0)
        }
    }

    override fun executeCommand(command: String): String {
        // Defense in depth: the privileged boundary re-runs the same policy engine as a
        // final gate. A command that is outright dangerous is refused here even if a
        // future refactor bypasses BridgeCoordinator/ShizukuBridge validation.
        val evaluation = CommandPolicyEngine().evaluate(command)
        if (evaluation.classification == PolicyClassification.BLOCKED) {
            return formatJsonResult(
                exitCode = -1,
                stdout = "",
                stderr = "Blocked by security policy: ${evaluation.reason}",
            )
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()

            formatJsonResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
        } catch (t: Throwable) {
            formatJsonResult(exitCode = -1, stdout = "", stderr = t.message ?: "Execution failed")
        }
    }

    private fun formatJsonResult(exitCode: Int, stdout: String, stderr: String): String {
        val cleanStdout = escapeJson(stdout)
        val cleanStderr = escapeJson(stderr)
        return """{"exitCode":$exitCode,"stdout":"$cleanStdout","stderr":"$cleanStderr"}"""
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }
    }
}
