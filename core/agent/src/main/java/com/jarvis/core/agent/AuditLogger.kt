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
        )
    fun redact(argsJson: String): String {
        val root =
            runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
                ?: return argsJson
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
        if (value is JsonPrimitive && sensitiveKeyParts.any { key.lowercase().contains(it) }) {
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
