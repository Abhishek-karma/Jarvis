package com.jarvis.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest


data class AuditRecord(
    val agentRunId: String?,
    val toolName: String,
    val tier: String,
    val paramsRedactedJson: String,
    val resultStatus: String,
    val userConfirmed: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)


fun interface AuditLogger {
    suspend fun record(entry: AuditRecord)
}


object AuditRedaction {
    // Keys matched by substring — long enough to be unambiguous.
    private val sensitiveKeyParts =
        listOf(
            "message",
            "body",
            "content",
            "text",
            "password",
            "passphrase",
            "secret",
            "token",
            "apikey",
            "api_key",
            "key",
            "code",
            "number",   // phone_number, contact numbers
            "phone",
            "url",
            "path",
        )

    // Keys matched exactly — too short for substring matching without collateral.
    private val sensitiveExactKeys = setOf("to")

    fun redact(argsJson: String): String {
        val root = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
            // Fail closed: an unparseable blob may contain sensitive data, so it is
            // replaced wholesale rather than passed through.
            ?: return "{\"error\":\"[redaction failed — unparseable args]\"}"
        return redactElement(root).toString()
    }

    private fun redactElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.map { (key, value) -> key to redactEntry(key, value) }.toMap())
            is JsonArray -> JsonArray(element.map { redactElement(it) })
            else -> element
        }

    private fun redactEntry(
        key: String,
        value: JsonElement,
    ): JsonElement {
        val lowerKey = key.lowercase()
        val sensitive = lowerKey in sensitiveExactKeys ||
            sensitiveKeyParts.any { lowerKey.contains(it) }
        if (value is JsonPrimitive && sensitive) {
            return JsonPrimitive(marker(value.content))
        }
        return redactElement(value)
    }

    private fun marker(value: String): String {


        val hash = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "[redacted len=${value.length} sha256=${hash.take(12)}]"
    }
}
