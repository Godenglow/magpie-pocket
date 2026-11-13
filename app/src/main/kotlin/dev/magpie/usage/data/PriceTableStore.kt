package dev.magpie.usage.data

import android.content.Context
import dev.magpie.usage.domain.pricing.PriceLookup
import dev.magpie.usage.domain.pricing.PriceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The price table: the bundled models.dev snapshot answers first, a newer
 * one the user fetched from https://models.dev/api.json takes over (the
 * desktop's bundled-then-sync pattern, catalog_bundled.go / fresh.go).
 */
class PriceTableStore(private val context: Context) {

    data class Loaded(
        val table: PriceTable?,
        /** Where the table came from, for honest labels. */
        val source: String,
        val fetchedAt: String,
    )

    private val dir = File(context.filesDir, "prices")
    private val userFile: File get() = File(dir, "models-dev.json")

    /** Loads the newest table: the user-fetched one, else the bundled asset. */
    fun load(): Loaded {
        userFile.takeIf { it.isFile }?.let { f ->
            runCatching {
                val table = PriceTable.parse(f.readText())
                if (!table.isEmpty()) {
                    return Loaded(table, "已更新", table.fetchedAt)
                }
            }
        }
        return runCatching {
            context.assets.open("prices/models-dev.json.gz").use { gz ->
                java.util.zip.GZIPInputStream(gz).use { stream ->
                    val text = stream.readBytes().toString(Charsets.UTF_8)
                    val table = PriceTable.parse(text)
                    Loaded(table, "内置快照", table.fetchedAt)
                }
            }
        }.getOrElse { Loaded(null, "无价格表", "") }
    }

    /**
     * Fetches models.dev/api.json, trims it to the price fields (the same
     * shape tools/fixtures/make_price_snapshot.py writes), saves it and
     * returns a lookup over it.
     */
    suspend fun refresh(): Loaded = withContext(Dispatchers.IO) {
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://models.dev/api.json")
            .header("User-Agent", "magpie-usage-android")
            .build()
        val body = http.newCall(request).execute().use { res ->
            when {
                res.code == 429 -> throw RuntimeException("请求过于频繁（HTTP 429），稍后再试")
                !res.isSuccessful -> throw RuntimeException("下载失败：HTTP ${res.code}")
                else -> res.body?.string() ?: throw RuntimeException("下载失败：空响应")
            }
        }
        val trimmed = trim(body)
        val table = PriceTable.parse(trimmed)
        if (table.isEmpty()) throw RuntimeException("价格表内容无效")
        dir.mkdirs()
        val tmp = File(dir, ".models-dev.json.tmp")
        tmp.writeText(trimmed)
        if (!tmp.renameTo(userFile)) {
            userFile.writeText(trimmed)
            tmp.delete()
        }
        Loaded(table, "已更新", table.fetchedAt)
    }

    companion object {
        /**
         * Keeps only provider -> model -> cost of models.dev's api.json, the
         * tiers read the way catalog.Price.UnmarshalJSON reads them (a tier
         * of another type, or of no size, is dropped; a missing part falls
         * back to the base price).
         *
         * canonical_model_id is kept too: PriceTable's maker lookup reads it
         * to tell a model's own maker from a reseller (catalog.go madeByOf),
         * and dropping it here would make an updated table price Claude and
         * DeepSeek calls at a relay's rate — the bundled snapshot keeps it,
         * so an update must not lose it.
         */
        fun trim(fullJson: String): String {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            val root = json.parseToJsonElement(fullJson).jsonObject
            val out = kotlinx.serialization.json.buildJsonObject {
                put("fetchedAt", kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()))
                put("source", kotlinx.serialization.json.JsonPrimitive("models.dev/api.json"))
                put("providers", kotlinx.serialization.json.buildJsonObject {
                    for ((pid, pv) in root) {
                        val models = (pv as? JsonObject)?.get("models") as? JsonObject ?: continue
                        val kept = kotlinx.serialization.json.buildJsonObject {
                            for ((mid, mv) in models) {
                                val mo = mv as? JsonObject ?: continue
                                val cost = mo["cost"] as? JsonObject ?: continue
                                val price = normalizeCost(cost)
                                if (price != null) put(mid, price)
                            }
                        }
                        if (kept.isNotEmpty()) put(pid, kept)
                    }
                })
                put("canonical", kotlinx.serialization.json.buildJsonObject {
                    for ((pid, pv) in root) {
                        val models = (pv as? JsonObject)?.get("models") as? JsonObject ?: continue
                        val kept = kotlinx.serialization.json.buildJsonObject {
                            for ((mid, mv) in models) {
                                val mo = mv as? JsonObject ?: continue
                                // Only priced entries, as make_price_snapshot.py
                                // keeps them: an unpriced one never answers.
                                if (mo["cost"] !is JsonObject) continue
                                val c = (mo["canonical_model_id"] as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.content
                                if (!c.isNullOrEmpty()) put(mid, kotlinx.serialization.json.JsonPrimitive(c))
                            }
                        }
                        if (kept.isNotEmpty()) put(pid, kept)
                    }
                })
            }
            return out.toString()
        }

        private fun normalizeCost(cost: JsonObject): kotlinx.serialization.json.JsonObject? {
            fun num(key: String): Double? =
                (cost[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

            val input = num("input") ?: 0.0
            val output = num("output") ?: 0.0
            val cacheRead = num("cache_read") ?: 0.0
            val cacheWrite = num("cache_write") ?: 0.0
            if (input == 0.0 && output == 0.0 && cacheRead == 0.0 && cacheWrite == 0.0) return null
            val cacheWrite1h = num("cache_write_1h")?.takeIf { it > 0 }
            val tiersIn = cost["tiers"] as? kotlinx.serialization.json.JsonArray
            val tiers = tiersIn?.mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                val tierObj = to["tier"] as? JsonObject
                val above: Long = when {
                    tierObj != null -> {
                        val type = (tierObj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (!type.isNullOrEmpty() && type != "context") return@mapNotNull null
                        (tierObj["size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    }
                    else -> (to["above"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                }
                if (above <= 0) return@mapNotNull null
                // A part the tier leaves out is the base price's part.
                fun part(key: String, base: Double): Double =
                    (to[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: base
                kotlinx.serialization.json.buildJsonObject {
                    put("above", kotlinx.serialization.json.JsonPrimitive(above))
                    put("input", kotlinx.serialization.json.JsonPrimitive(part("input", input)))
                    put("output", kotlinx.serialization.json.JsonPrimitive(part("output", output)))
                    put("cache_read", kotlinx.serialization.json.JsonPrimitive(part("cache_read", cacheRead)))
                    put("cache_write", kotlinx.serialization.json.JsonPrimitive(part("cache_write", cacheWrite)))
                    put("cache_write_1h", kotlinx.serialization.json.JsonPrimitive(part("cache_write_1h", 0.0)))
                }
            }?.sortedBy { (it["above"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
                .orEmpty()
            return kotlinx.serialization.json.buildJsonObject {
                put("input", kotlinx.serialization.json.JsonPrimitive(input))
                put("output", kotlinx.serialization.json.JsonPrimitive(output))
                put("cache_read", kotlinx.serialization.json.JsonPrimitive(cacheRead))
                put("cache_write", kotlinx.serialization.json.JsonPrimitive(cacheWrite))
                cacheWrite1h?.let { put("cache_write_1h", kotlinx.serialization.json.JsonPrimitive(it)) }
                if (tiers.isNotEmpty()) {
                    put("tiers", kotlinx.serialization.json.JsonArray(tiers))
                }
            }
        }
    }
}

/** The live lookup the app prices with, plus where its table came from. */
data class PriceSource(val lookup: PriceLookup?, val source: String, val fetchedAt: String) {
    companion object {
        val EMPTY = PriceSource(null, "无价格表", "")
    }
}

fun PriceTableStore.Loaded.toSource(): PriceSource =
    PriceSource(table?.let { PriceLookup(it) }, source, fetchedAt)
