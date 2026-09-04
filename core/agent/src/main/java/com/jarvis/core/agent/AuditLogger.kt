package com.jarvis.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * One append-only audit record per tool execution (14-SECURITY.md §7). Never contains
 * plaintext sensitive values — [AuditRecord.paramsRedactedJson] is redacted first.
 */
data class AuditRecord(
    val agentRunId: String?, // nullable — some tool calls happen outside a full agent run
    val toolName: String,
    val tier: String, // "read_only" | "reversible_write" | "sensitive"
    val paramsRedactedJson: String,
    val resultStatus: String, // "success" | "failure" | "cancelled"
    val userConfirmed: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Storage hook for the audit log. A Room-backed, DAO-append-only implementation lands with
 * the v0.5 "audit log" roadmap bullet (09-DATA-MODELS.md §2); the engine only depends on
 * this seam so it never decides how rows are persisted.
 */
fun interface AuditLogger {
    suspend fun record(entry: AuditRecord)
}

/**
 * Replaces values under known-sensitive argument keys with a length + hash marker —
 * never plaintext (jarvis-agent-tool §4, 14-SECURITY.md §7). Nested objects and arrays
 * are walked so e.g. message bodies inside a `messages` list are caught too.
 */
object AuditRedaction {
    private val sensitiveKeyParts = listOf(
        "message", "body", "content", "text", "password", "passphrase",
        "secret", "token", "apikey", "api_key", "key", "code",
    )
    private val sha256 = MessageDigest.getInstance("SHA-256")

    fun redact(argsJson: String): String {
        val root = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
            ?: return argsJson
        return redactElement(root).toString()
    }

    private fun redactElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.map { (key, value) -> key to redactEntry(key, value) }.toMap())
        is JsonArray -> JsonArray(element.map { redactElement(it) })
        else -> element
    }

    private fun redactEntry(key: String, value: JsonElement): JsonElement {
        if (value is JsonPrimitive && sensitiveKeyParts.any { key.lowercase().contains(it) }) {
            return JsonPrimitive(marker(value.content))
        }
        return redactElement(value)
    }

    private fun marker(value: String): String {
        val hash = sha256.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return "[redacted len=${value.length} sha256=${hash.take(12)}]"
    }
}
