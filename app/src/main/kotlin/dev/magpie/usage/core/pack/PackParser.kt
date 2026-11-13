package dev.magpie.usage.core.pack

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.model.SharedDayPack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses the decrypted day pack: a SharedDay JSON of one computer's calls of
 * one day. Unknown fields are tolerated; a missing optional field is kept
 * absent, never read as "known to be zero" where it would change a figure.
 *
 * Reference: yetone/magpie @ 6acf5ee5, internal/usage/shared.go SharedDay.
 */
object PackParser {

    /** Parse version bumps when field handling changes; stored per file.
     * 2 adds session_provider (pricing a session-unknown row);
     * 3 adds host + providerAccount (the 账号 dimension). */
    const val PARSE_VERSION = 3

    class ParseError(message: String) : Exception(message)

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(plain: ByteArray, fileName: String): SharedDayPack {
        val root = try {
            lenientJson.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw ParseError("用量包不是有效的 JSON: ${e.message}")
        }
        val computer = root.string("computer") ?: throw ParseError("用量包缺少 computer 字段")
        val day = root.string("day") ?: throw ParseError("用量包缺少 day 字段")
        val name = root.string("name") ?: ""
        val callsJson = root["calls"] as? kotlinx.serialization.json.JsonArray
            ?: throw ParseError("用量包缺少 calls 数组")
        val calls = ArrayList<CallRow>(callsJson.size)
        for ((index, element) in callsJson.withIndex()) {
            val obj = try {
                element.jsonObject
            } catch (e: Exception) {
                throw ParseError("第 $index 条记录不是 JSON 对象")
            }
            calls.add(call(obj, fileName, index, computer, day))
        }
        return SharedDayPack(computer, name, day, calls)
    }

    private fun call(o: JsonObject, fileName: String, index: Int, computer: String, day: String): CallRow {
        fun str(key: String): String = o.string(key) ?: ""
        fun long(key: String): Long = o.long(key) ?: 0L
        fun bool(key: String): Boolean = o.bool(key) ?: false
        val tMs = parseTime(o["t"]) ?: throw ParseError("第 $index 条记录缺少有效时间 t")
        return CallRow(
            fileName = fileName,
            ordInFile = index,
            computer = o.string("computer") ?: computer,
            day = day,
            t = tMs,
            agent = str("agent"),
            provider = str("provider"),
            model = str("model"),
            requested = str("req"),
            served = str("served"),
            inTok = long("in"),
            outTok = long("out"),
            cacheRead = long("cache_read"),
            cacheWrite = long("cache_write"),
            cacheWrite1h = long("cache_write_1h"),
            cacheUnknown = bool("cache_unknown"),
            reasoning = long("reasoning"),
            effort = str("effort"),
            ms = long("ms"),
            ttftMs = long("ttft_ms"),
            firstTextMs = long("first_text_ms"),
            status = o.long("status")?.toInt() ?: 0,
            err = str("err"),
            errType = str("err_type"),
            rid = str("rid"),
            session = str("session"),
            sessionProvider = str("session_provider"),
            host = str("host"),
            providerAccount = str("providerAccount"),
            source = o.string("source") ?: "",
            tries = o.long("tries")?.toInt() ?: 0,
            handedOn = bool("handed_on"),
            rejected = bool("rejected"),
            costUsd = o.doubleOrNull("cost"),
            credits = o.doubleOrNull("credits"),
            upBytes = long("up_bytes"),
            downBytes = long("down_bytes"),
            raw = o.toString(),
        )
    }

    /**
     * Record.Time is Go's time.Time JSON: RFC 3339 with an offset or Z.
     * The numeric epoch forms (seconds or milliseconds) are also accepted for
     * tolerance, though the desktop does not write them.
     */
    fun parseTime(value: kotlinx.serialization.json.JsonElement?): Long? {
        if (value == null || value is JsonNull) return null
        val prim = value as? kotlinx.serialization.json.JsonPrimitive ?: return null
        prim.longOrNull?.let { secondsOrMillis ->
            // 10^12 separates 2001-09 in seconds (≤4.1e11) from milliseconds.
            return if (secondsOrMillis > 1_000_000_000_000L) secondsOrMillis else secondsOrMillis * 1000
        }
        val text = prim.content
        return try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }

    private fun JsonObject.long(key: String): Long? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p.isString) return p.content.toLongOrNull()
        return p.longOrNull ?: p.content.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p.isString) {
            return when (p.content.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        return p.booleanOrNull
    }

    private fun JsonObject.doubleOrNull(key: String): Double? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p.isString) return p.content.toDoubleOrNull()
        return p.content.toDoubleOrNull()
    }
}
