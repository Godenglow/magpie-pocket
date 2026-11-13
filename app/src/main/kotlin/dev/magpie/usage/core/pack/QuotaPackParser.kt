package dev.magpie.usage.core.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The desktop's shared quota history (.magpie-quotas): a quotaHist JSON of
 * "provider|user" -> window name -> readings, each {at, left, start?,
 * resetsAt?}. The phone stores its points and merges across computers by
 * (account, window, at) — the same union MergeQuotaHistory computes
 * (yetone/magpie @ 6acf5ee5, internal/provider/quota_history.go).
 */
object QuotaPackParser {

    data class QuotaPointParsed(
        val provider: String,
        val user: String,
        val windowName: String,
        val at: Long,
        val left: Double,
        val startAt: Long?,
        val resetsAt: Long?,
    )

    class ParseError(message: String) : Exception(message)

    fun parse(plain: ByteArray): List<QuotaPointParsed> {
        val root = try {
            Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw ParseError("额度历史不是有效的 JSON: ${e.message}")
        }
        val out = ArrayList<QuotaPointParsed>(256)
        for ((accountKey, windowsEl) in root) {
            val windows = windowsEl as? JsonObject ?: continue
            val sep = accountKey.indexOf('|')
            if (sep <= 0) continue
            val provider = accountKey.substring(0, sep)
            val user = accountKey.substring(sep + 1)
            for ((windowName, pointsEl) in windows) {
                val points = pointsEl as? kotlinx.serialization.json.JsonArray ?: continue
                for (p in points) {
                    val po = p as? JsonObject ?: continue
                    val at = timeOf(po["at"]) ?: continue
                    val left = (po["left"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: continue
                    out.add(
                        QuotaPointParsed(
                            provider = provider,
                            user = user,
                            windowName = windowName,
                            at = at,
                            left = left,
                            startAt = timeOf(po["start"]),
                            resetsAt = timeOf(po["resetsAt"]),
                        )
                    )
                }
            }
        }
        return out
    }

    private fun timeOf(el: kotlinx.serialization.json.JsonElement?): Long? {
        if (el == null || el is JsonNull) return null
        val prim = el as? kotlinx.serialization.json.JsonPrimitive ?: return null
        prim.longOrNull?.let { return if (it > 1_000_000_000_000L) it else it * 1000 }
        return try {
            java.time.Instant.parse(prim.content).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(prim.content).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }
}
