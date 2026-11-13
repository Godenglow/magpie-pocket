package dev.magpie.usage.testsupport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Tiny JSON → plain Java types for test expectations. */
object MiniJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Any? = element(json.parseToJsonElement(text))

    private fun element(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.entries.associate { it.key to element(it.value) }
        is JsonArray -> e.map { element(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull ?: e.content
        }
    }
}
