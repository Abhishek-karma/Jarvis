package com.jarvis.core.network

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * Minimal JSON DOM adapter — Moshi has no tree API, but tool schemas need to travel as
 * arbitrary JSON objects inside generated DTOs. Reads any JSON value into plain Kotlin
 * types (Map/List/String/Double/Boolean/null) and writes them back verbatim.
 */
object JsonTreeAdapter : JsonAdapter<Any?>() {
    override fun fromJson(reader: JsonReader): Any? =
        when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                val result = LinkedHashMap<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) result[reader.nextName()] = fromJson(reader)
                reader.endObject()
                result
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val result = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) result.add(fromJson(reader))
                reader.endArray()
                result
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextDouble()
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> {
                reader.nextNull<Any?>()
                null
            }
            else -> throw IllegalStateException("Unexpected JSON token ${reader.peek()}")
        }

    override fun toJson(
        writer: JsonWriter,
        value: Any?,
    ) {
        when (value) {
            null -> writer.nullValue()
            is String -> writer.value(value)
            is Boolean -> writer.value(value)
            is Double -> writer.value(value)
            is Float -> writer.value(value)
            is Int -> writer.value(value)
            is Long -> writer.value(value)
            is Number -> writer.value(value.toDouble())
            is Map<*, *> -> {
                writer.beginObject()
                value.forEach { (key, item) ->
                    writer.name(key.toString())
                    toJson(writer, item)
                }
                writer.endObject()
            }
            is List<*> -> {
                writer.beginArray()
                value.forEach { toJson(writer, it) }
                writer.endArray()
            }
            else -> throw IllegalArgumentException("Cannot serialize ${value.javaClass} as JSON")
        }
    }
}
