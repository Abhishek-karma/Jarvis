package com.jarvis.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates a tool-call's `argsJson` against the tool's declared JSON-Schema before dispatch
 * (untrusted callers must not reach a tool with malformed input).
 *
 * Guard semantics, not a full JSON-Schema validator: it enforces that the args parse as a
 * JSON object, that every required key is present, and that present values match the
 * declared structural type (object/array/boolean/number). Primitives are coerced leniently
 * (a JSON string "5" counts as numeric) — tools still parse defensively.
 */
class ToolArgsValidator {
    sealed class Result {
        data object Valid : Result()

        data class Rejected(
            val reason: String,
        ) : Result()
    }

    fun validate(
        schemaJson: String,
        argsJson: String,
    ): Result {
        val args =
            runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
                ?: return Result.Rejected("Arguments are not valid JSON.")

        val schema =
            runCatching { Json.parseToJsonElement(schemaJson) as? JsonObject }.getOrNull()
                ?: return Result.Valid // no parseable schema means no declared constraints
        if ((schema["type"] as? JsonPrimitive)?.content != "object") return Result.Valid

        (schema["required"] as? JsonArray).orEmpty().forEach { keyElement ->
            val key = (keyElement as? JsonPrimitive)?.content ?: return@forEach
            if (!args.containsKey(key)) {
                return Result.Rejected("Missing required argument '$key'.")
            }
        }

        val properties = schema["properties"] as? JsonObject ?: return Result.Valid
        for ((key, propertyJson) in properties) {
            if (!args.containsKey(key)) continue
            val expectedType = (propertyJson as? JsonObject)?.get("type") as? JsonPrimitive ?: continue
            val actual = args[key]
            if (!typeMatches(expectedType.content, actual)) {
                return Result.Rejected("Argument '$key' must be of type ${expectedType.content}.")
            }
        }
        return Result.Valid
    }

    private fun typeMatches(
        expectedType: String,
        actual: JsonElement?,
    ): Boolean =
        when (expectedType) {
            "string" -> actual is JsonPrimitive
            "boolean" -> (actual as? JsonPrimitive)?.let { it.content == "true" || it.content == "false" } == true
            "number" -> numeric(actual)
            "integer" -> numeric(actual) && (actual as JsonPrimitive).content.toDouble() % 1.0 == 0.0
            "array" -> actual is JsonArray
            "object" -> actual is JsonObject
            else -> true // unconstrained/unknown types pass; tools still parse defensively
        }

    private fun numeric(actual: JsonElement?): Boolean {
        val content = (actual as? JsonPrimitive)?.content ?: return false
        return content != "true" && content != "false" && content.toDoubleOrNull() != null
    }
}
