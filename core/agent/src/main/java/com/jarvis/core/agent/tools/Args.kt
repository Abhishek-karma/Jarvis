package com.jarvis.core.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


internal class Args
    private constructor(
        private val json: JsonObject,
    ) {
        fun string(key: String): String? {
            val element = json[key] ?: return null
            if (element is JsonPrimitive) {
                return element.content.takeIf { it.isNotEmpty() }
            }
            if (element is kotlinx.serialization.json.JsonArray) {
                val first = element.firstOrNull() as? JsonPrimitive
                return first?.content?.takeIf { it.isNotEmpty() }
            }
            return null
        }

        fun long(key: String): Long? {
            val raw = (json[key] as? JsonPrimitive)?.content?.trim() ?: return null
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        }

        fun int(key: String): Int? = long(key)?.toInt()

        fun double(key: String): Double? =
            (json[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()

        fun boolean(key: String): Boolean? =
            (json[key] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes", "on", "enable", "enabled", "start" -> true
                    "false", "0", "no", "off", "disable", "disabled", "stop" -> false
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
