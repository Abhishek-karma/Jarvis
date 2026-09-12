package com.jarvis.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


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
                ?: return Result.Valid
        if ((schema["type"] as? JsonPrimitive)?.content != "object") return Result.Valid

        (schema["required"] as? JsonArray).orEmpty().forEach { keyElement ->
            val key = (keyElement as? JsonPrimitive)?.content ?: return@forEach
            val matchingKey = findMatchingKey(args, key)
            if (matchingKey == null) {
                return Result.Rejected("Missing required argument '$key'.")
            }
        }

        val properties = schema["properties"] as? JsonObject ?: return Result.Valid
        for ((key, propertyJson) in properties) {
            val actualKey = findMatchingKey(args, key) ?: continue
            val expectedType = (propertyJson as? JsonObject)?.get("type") as? JsonPrimitive ?: continue
            val actual = args[actualKey]
            if (!typeMatches(expectedType.content, actual)) {
                return Result.Rejected("Argument '$key' must be of type ${expectedType.content}.")
            }
        }
        return Result.Valid
    }

    private fun findMatchingKey(args: JsonObject, key: String): String? {
        if (args.containsKey(key)) return key
        val camel = snakeToCamel(key)
        if (args.containsKey(camel)) return camel
        val snake = camelToSnake(key)
        if (args.containsKey(snake)) return snake
        return when (key) {
            "package_name" -> listOf("packageName", "app_name", "appName", "target", "app").firstOrNull { args.containsKey(it) }
            "file_name" -> listOf("filename", "name", "path").firstOrNull { args.containsKey(it) }
            "text" -> listOf("content", "message", "body").firstOrNull { args.containsKey(it) }
            "message" -> listOf("text", "content", "body").firstOrNull { args.containsKey(it) }
            "duration_seconds" -> listOf("durationSeconds", "seconds", "duration").firstOrNull { args.containsKey(it) }
            "at_utc_millis" -> listOf("atUtcMillis", "timestamp", "time").firstOrNull { args.containsKey(it) }
            "start_utc_millis" -> listOf("startUtcMillis", "start").firstOrNull { args.containsKey(it) }
            "end_utc_millis" -> listOf("endUtcMillis", "end").firstOrNull { args.containsKey(it) }
            "from_utc_millis" -> listOf("fromUtcMillis", "from", "start").firstOrNull { args.containsKey(it) }
            "to_utc_millis" -> listOf("toUtcMillis", "to", "end").firstOrNull { args.containsKey(it) }
            "remind_at_utc_millis" -> listOf("remindAtUtcMillis", "remind_at", "remindAt", "time", "timestamp").firstOrNull { args.containsKey(it) }
            else -> null
        }
    }

    private fun snakeToCamel(name: String): String {
        val parts = name.split('_')
        if (parts.size <= 1) return name
        return parts.first() + parts.drop(1).joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun camelToSnake(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            if (c.isUpperCase()) {
                sb.append('_').append(c.lowercaseChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun typeMatches(
        expectedType: String,
        actual: JsonElement?,
    ): Boolean =
        when (expectedType) {
            "string" -> actual is JsonPrimitive
            "boolean" -> (actual as? JsonPrimitive)?.let {
                val c = it.content.lowercase()
                c == "true" || c == "false" || c == "1" || c == "0" || c == "yes" || c == "no"
            } == true
            "number" -> numeric(actual)
            "integer" -> numeric(actual) && (actual as JsonPrimitive).content.toDoubleOrNull()?.let { it % 1.0 == 0.0 } == true
            "array" -> actual is JsonArray
            "object" -> actual is JsonObject
            else -> true
        }

    private fun numeric(actual: JsonElement?): Boolean {
        val content = (actual as? JsonPrimitive)?.content ?: return false
        return content != "true" && content != "false" && content.toDoubleOrNull() != null
    }
}
