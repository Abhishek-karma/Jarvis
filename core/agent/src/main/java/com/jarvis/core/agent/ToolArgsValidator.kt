package com.jarvis.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
        val argsElement = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
            ?: return Result.Rejected("Arguments are not valid JSON.")
        val args = argsElement as? JsonObject
            ?: return Result.Rejected("Arguments must be a JSON object.")

        if (schemaJson.isBlank() || schemaJson.trim() == "{}") return Result.Valid
        val schemaElement = runCatching { Json.parseToJsonElement(schemaJson) }.getOrNull()
            ?: return Result.Rejected("Tool schema definition is malformed JSON.")
        val schema = schemaElement as? JsonObject
            ?: return Result.Rejected("Tool schema must define an object type.")

        if (schema.containsKey("type") && (schema["type"] as? JsonPrimitive)?.content != "object") {
            return Result.Rejected("Tool schema must define an object type.")
        }

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
            val propObj = propertyJson as? JsonObject ?: continue
            val expectedType = (propObj["type"] as? JsonPrimitive)?.content
            val actual = args[actualKey] ?: continue

            if (expectedType != null && !typeMatches(expectedType, actual)) {
                return Result.Rejected("Argument '$key' must be of type $expectedType.")
            }

            val enumArray = propObj["enum"] as? JsonArray
            if (enumArray != null && !enumMatches(enumArray, actual)) {
                return Result.Rejected("Argument '$key' must be one of ${enumArray}.")
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
            "number" -> listOf("phone", "phoneNumber", "phone_number", "to", "recipient").firstOrNull { args.containsKey(it) }
            "to" -> listOf("number", "phone", "phoneNumber", "phone_number", "recipient").firstOrNull { args.containsKey(it) }
            "body" -> listOf("text", "message", "content").firstOrNull { args.containsKey(it) }
            "file_name" -> listOf("filename", "name", "path", "file").firstOrNull { args.containsKey(it) }
            "content" -> listOf("text", "message", "body", "data").firstOrNull { args.containsKey(it) }
            "text" -> listOf("content", "message", "body", "data").firstOrNull { args.containsKey(it) }
            "message" -> listOf("text", "content", "body").firstOrNull { args.containsKey(it) }
            "expression" -> listOf("expr", "equation", "formula", "input", "query", "math").firstOrNull { args.containsKey(it) }
            "query" -> listOf("q", "search", "text", "term", "queries", "keywords", "input").firstOrNull { args.containsKey(it) }
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
        actual: JsonElement,
    ): Boolean =
        when (expectedType) {
            "string" -> actual is JsonPrimitive && actual.isString
            "boolean" -> actual is JsonPrimitive && !actual.isString && actual.booleanOrNull != null
            "number" -> actual is JsonPrimitive && !actual.isString && actual.doubleOrNull != null
            "integer" -> actual is JsonPrimitive && !actual.isString && actual.longOrNull != null
            "array" -> actual is JsonArray
            "object" -> actual is JsonObject
            else -> true
        }

    private fun enumMatches(
        enumArray: JsonArray,
        actual: JsonElement,
    ): Boolean {
        return enumArray.any { allowed ->
            if (allowed is JsonPrimitive && actual is JsonPrimitive) {
                allowed.isString == actual.isString && allowed.content == actual.content
            } else {
                allowed == actual
            }
        }
    }
}
