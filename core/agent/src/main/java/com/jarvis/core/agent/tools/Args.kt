package com.jarvis.core.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient JSON argument reader shared by the tool factories: values arrive as strings or
 * numbers from the model, so numeric getters accept both spellings.
 */
internal class Args
    private constructor(
        private val json: JsonObject,
    ) {
        fun string(key: String): String? =
            (json[key] as? JsonPrimitive)
                ?.content
                ?.takeIf { it.isNotEmpty() }

        fun long(key: String): Long? {
            val raw = (json[key] as? JsonPrimitive)?.content?.trim() ?: return null
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        }

        fun boolean(key: String): Boolean? =
            (json[key] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }

        companion object {
            /** Null when [argsJson] is not a JSON object — the tool reports a clean failure. */
            fun parse(argsJson: String): Args? =
                runCatching {
                    (Json.parseToJsonElement(argsJson) as? JsonObject)?.let(::Args)
                }.getOrNull()
        }
    }
