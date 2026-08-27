package com.leeotts.cicero.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** Minimal JSON-Schema helpers, so tool specs stay readable. */
object Schemas {

    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (name, schema) -> put(name, schema) }
            }
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(it) } }
            }
            // Gemini and Claude both accept this; it keeps models from inventing fields.
            put("additionalProperties", false)
        }

    fun string(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun integer(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun enumOf(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    val empty: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }
}

/** Reads a string argument, tolerating a model that omits or nulls it. */
fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.ifBlank { null }

/** Reads an integer argument; models sometimes send it as a quoted string. */
fun JsonObject.int(key: String): Int? =
    this[key]?.let { runCatching { it.jsonPrimitive.content.trim().toInt() }.getOrNull() }
