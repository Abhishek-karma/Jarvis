package com.jarvis.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Recursively sorts JSON object keys so structurally identical args produce one canonical form. */
internal fun canonicalizeJsonElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (k, v) -> k to canonicalizeJsonElement(v) })
    is JsonArray -> JsonArray(element.map(::canonicalizeJsonElement))
    else -> element
}
