package dev.magpie.usage.domain.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone's price table: a trimmed models.dev snapshot
 * (provider -> model -> cost), the bundled asset or a newer one the user
 * fetched from https://models.dev/api.json.
 *
 * Lookups follow the desktop's catalogue in a form the record fields alone
 * support (catalog.go pricedBy / MakerPriced; provider/models.go tariffOf):
 *
 *   1. priceOf(providerId, modelId) — exact, case/dash/snapshot normalised;
 *   2. makerPrice(modelId) — the one provider that lists the model as its
 *      own, the maker-looking one first when several do;
 *   3. callers fall back to the served name and the session provider.
 *
 * What the desktop has and the phone does not: user-set prices, a provider's
 * price rate and custom rate windows — so a table price here is the public
 * list price, and the fee is labelled as an estimate.
 */
class PriceTable private constructor(
    val fetchedAt: String,
    private val providers: Map<String, Map<String, MagpiePrice>>,
    /** provider id -> model id -> canonical "<maker>/<id>" (models.dev). */
    private val canonical: Map<String, Map<String, String>>,
) {

    /** Provider ids that look like a model's maker, for maker scan ordering. */
    private val makerIds = setOf(
        "openai", "anthropic", "deepseek", "google", "xai", "moonshotai", "moonshot",
        "zhipu", "zai", "qwen", "mistral", "minimax", "meta", "mistralai", "baidu",
        "doubao", "volcengine", "stepfun", "01-ai", "cov", "zhipuai",
    )

    /**
     * A model id's prefix at its maker's own API, where it differs from the
     * provider id: Anthropic's models are claude-*, Zhipu's glm-*, Google's
     * gemini-* — the desktop reads this off each provider's catalog, and the
     * phone carries the handful that matter as a maker with no canonical.
     */
    private val makerPrefixes = mapOf(
        "anthropic" to listOf("claude-"),
        "zhipuai" to listOf("glm-", "chatglm-"),
        "zhipu" to listOf("glm-", "chatglm-"),
        "zai" to listOf("glm-", "chatglm-"),
        "openai" to listOf("gpt-", "o1", "o3", "o4"),
        "google" to listOf("gemini-", "gemma-"),
        "xai" to listOf("grok-"),
        "moonshotai" to listOf("kimi-"),
        "moonshot" to listOf("kimi-"),
        "qwen" to listOf("qwen"),
        "minimax" to listOf("minimax-", "abab"),
        "baidu" to listOf("ernie-"),
        "stepfun" to listOf("step-"),
    )

    fun priceOf(providerId: String, modelId: String): MagpiePrice? {
        val models = providers[providerId.lowercase()] ?: return null
        val price = lookupIn(models, modelId) ?: return null
        return MagpiePrice.withOneHourFor(modelId, price)
            .copy(peak = dev.magpie.usage.domain.pricing.Peak.forCall(providerId, modelId, emptyList()))
    }

    /**
     * The maker's list price of a model — the desktop's MakerPriced
     * (catalog.go:1153 + madeByOf) with its canonicallyPriced fallback
     * (catalog.go:1052):
     *
     *  1. A provider that lists the model as its OWN (its canonical_model_id
     *     names it, and its id is that maker's API — ownAPI) answers with its
     *     own entry's price. A reseller that merely carries the model is not
     *     the maker: Mistral resells deepseek-v4.1-flash at $0.30/$1.20 while
     *     DeepSeek's own price is $0.15/$0.60, and reading the reseller would
     *     overcharge by 2×.
     *  2. An entry's own price indexed under the id models.dev says it is:
     *     DeepSeek sells V4.1 Flash as deepseek-flash (canonical
     *     deepseek/deepseek-v4.1-flash-1017) while the relays serve it as
     *     deepseek-v4.1-flash — so that entry's price answers for this id.
     *  3. Nothing says who makes it: the first provider by id that prices it,
     *     never a "-plan" catalogue (a membership's charge, not the model's).
     */
    fun makerPrice(modelId: String): MagpiePrice? {
        val bare = bareId(modelId)
        val bareDashed = dashed(bare)

        // 1. The maker's own entry: the provider models.dev's canonical ids
        //    name as this model's maker, with that maker's own id of it. A
        //    reseller that merely carries the model is not the maker (Mistral
        //    resells deepseek-v4.1-flash at $0.30/$1.20 where DeepSeek's own
        //    price is $0.15/$0.60, and reading the reseller would overcharge
        //    the call by 2×).
        makerOf(bare)?.let { pid ->
            priceOf(pid, modelId)?.let { return it }
        }
        // 1b. No canonical says who makes it, but a provider id that IS the
        //     model's vendor lists it under this very id: anthropic's
        //     claude-opus-4-7 carries no canonical_model_id at all, and it is
        //     the price a Claude call must read, not 302ai's resale of it.
        //     A vendor's id matches when the model's name starts with it
        //     (anthropic→claude-*, openai→gpt-*, deepseek→deepseek-*).
        for (pid in providers.keys.sortedWith(compareBy({ p: String -> if (p in makerIds) 0 else 1 }, { p: String -> p }))) {
            if (pid.contains("-plan")) continue
            val prefixes = makerPrefixes[pid]
            val isMaker = pid in makerIds && (
                bare.startsWith("$pid-") || prefixes?.any { bare.startsWith(it) } == true
                )
            if (!isMaker) continue
            priceOf(pid, modelId)?.let { return it }
        }
        for ((pid, models) in providers) {
            if (pid.contains("-plan")) continue
            for ((mid, price) in models) {
                val canon = canonicalFor(pid, mid) ?: continue
                if (!ownApi(pid, canon.substringBefore('/').lowercase())) continue
                val cid = bareId(canon.substringAfter('/'))
                if (cid == bare || dashed(cid) == bareDashed || snapshotOf(dashed(cid)) == bareDashed) {
                    return MagpiePrice.withOneHourFor(modelId, price)
                        .copy(peak = Peak.forCall(pid, modelId, emptyList()))
                }
            }
        }

        // 2. canonicallyPriced: an entry's own price under the canonical id
        //    models.dev records, so a maker's renamed listing answers for the
        //    id the agents use. The maker's own API answers first: 302ai and
        //    sap-ai-core resell Anthropic's models at their own markup, and
        //    reading them for claude-opus-4-7 would bill at a relay's price.
        val maker = makerOf(bare)
        val canonicalHits = ArrayList<Pair<String, MagpiePrice>>(2)
        for ((pid, models) in providers) {
            if (pid.contains("-plan")) continue
            val per = canonical[pid] ?: continue
            for ((mid, price) in models) {
                val canon = per[mid] ?: per[bareId(mid)] ?: continue
                val cidDashed = dashed(bareId(canon.substringAfter('/')))
                if (bareDashed == cidDashed || bareDashed == snapshotOf(cidDashed)) {
                    canonicalHits.add(pid to price)
                    break
                }
            }
        }
        canonicalHits
            .sortedWith(
                compareBy(
                    { (pid, _): Pair<String, MagpiePrice> -> if (pid == maker) 0 else 1 },
                    { (pid, _): Pair<String, MagpiePrice> -> if (claimsItselfAsMaker(pid, bare)) 0 else 1 },
                    { (pid, _): Pair<String, MagpiePrice> -> if (pid in makerIds) 0 else 1 },
                    { it.first },
                )
            )
            .firstOrNull()
            ?.let { (pid, price) ->
                return MagpiePrice.withOneHourFor(modelId, price)
                    .copy(peak = Peak.forCall(pid, modelId, emptyList()))
            }

        // 3. Nothing says who makes it: first provider by id that prices it.
        val matches = ArrayList<Pair<String, MagpiePrice>>(2)
        for ((pid, models) in providers) {
            if (pid.contains("-plan")) continue
            val price = lookupIn(models, modelId) ?: continue
            matches.add(pid to price)
        }
        if (matches.isEmpty()) return null
        val chosen = when {
            matches.size == 1 -> matches[0]
            else -> matches.firstOrNull { (pid, _) -> pid in makerIds }
                ?: matches.minByOrNull { (pid, _) -> pid }
        } ?: matches[0]
        return MagpiePrice.withOneHourFor(modelId, chosen.second)
            .copy(peak = Peak.forCall(chosen.first, modelId, emptyList()))
    }

    /** models.dev's canonical_model_id of pid's model, bare-id matched. */
    private fun canonicalFor(pid: String, mid: String): String? {
        val per = canonical[pid] ?: return null
        return per[mid] ?: per[bareId(mid)]
    }

    /**
     * Whether pid's entry for this model claims pid itself as the maker:
     * anthropic's claude-opus-4-7 is "anthropic/claude-opus-4-7", while
     * amazon-bedrock's and 302ai's are canonical to anthropic and to
     * themselves respectively — a cloud or a relay, not the maker.
     */
    private fun claimsItselfAsMaker(pid: String, bare: String): Boolean {
        val per = canonical[pid] ?: return false
        val bareDashed = dashed(bare)
        for ((mid, canon) in per) {
            val maker = canon.substringBefore('/')
            if (!ownApi(pid, maker)) continue
            val cid = bareId(canon.substringAfter('/'))
            if (cid == bare || dashed(cid) == bareDashed || snapshotOf(dashed(cid)) == bareDashed) return true
            if (bareId(mid) == bare || dashed(bareId(mid)) == bareDashed) return true
        }
        return false
    }

    /**
     * The maker of [bare] by models.dev's canonical ids: the provider whose
     * entry for this model is canonical to that very provider (openai's
     * gpt-6.1-sol is "openai/gpt-6.1-sol"). Empty when nobody lists it as
     * its own — the relays only, or a model models.dev has no maker for.
     */
    private fun makerOf(bare: String): String? {
        val bareDashed = dashed(bare)
        for ((pid, models) in providers) {
            if (pid.contains("-plan")) continue
            val per = canonical[pid] ?: continue
            for (mid in models.keys) {
                val canon = per[mid] ?: per[bareId(mid)] ?: continue
                val maker = canon.substringBefore('/')
                if (!ownApi(pid, maker)) continue
                val cid = bareId(canon.substringAfter('/'))
                if (cid == bare || dashed(cid) == bareDashed || snapshotOf(dashed(cid)) == bareDashed) {
                    return pid
                }
            }
        }
        return null
    }

    private fun lookupIn(models: Map<String, MagpiePrice>, modelId: String): MagpiePrice? {
        // The catalogue's own spellings: exact, dots as dashes, case-folded,
        // a trailing -MMDD snapshot suffix, a trailing :tag (Ollama).
        val candidates = linkedSetOf(modelId, modelId.lowercase())
        val bare = modelId.substringAfter('/')
        candidates.add(bare)
        candidates.add(bare.lowercase())
        candidates.addAll(candidates.toList().map { it.replace('.', '-') })
        candidates.addAll(candidates.filter { SNAPSHOT_SUFFIX.matches(it) }.map { SNAPSHOT_SUFFIX.replace(it, "") })
        candidates.addAll(candidates.filter { it.contains(':') }.map { it.substringBefore(':') })
        for (key in candidates) {
            models[key]?.let { return it }
        }
        return null
    }

    fun isEmpty(): Boolean = providers.isEmpty()

    companion object {
        private val SNAPSHOT_SUFFIX = Regex("-\\d{4}$")

        /** ownAPI (catalog.go:1092): pid is the maker's own API, not a plan's. */
        private fun ownApi(pid: String, maker: String): Boolean {
            val p = pid.lowercase()
            val m = maker.lowercase()
            return (p == m || p.startsWith("$m-")) && !p.contains("-plan")
        }

        /** bareID: the id without a path, lower-cased (catalog.go:2088). */
        private fun bareId(id: String): String = id.substringAfterLast('/').lowercase()

        private fun dashed(id: String): String = id.replace('.', '-')

        /** snapshotOf: the id without its snapshot date (catalog.go:1187). */
        private fun snapshotOf(id: String): String = SNAPSHOT_SUFFIX.replace(id, "")

        /** Parses the trimmed snapshot JSON (see tools/fixtures/make_price_snapshot.py). */
        fun parse(jsonText: String): PriceTable {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val fetchedAt = root["fetchedAt"]?.jsonPrimitive?.content ?: ""
            val providersJson = (root["providers"] as? JsonObject) ?: JsonObject(emptyMap())
            val out = HashMap<String, Map<String, MagpiePrice>>(providersJson.size)
            for ((pid, pv) in providersJson) {
                val modelsJson = pv as? JsonObject ?: continue
                val models = HashMap<String, MagpiePrice>(modelsJson.size)
                for ((mid, mv) in modelsJson) {
                    val o = mv as? JsonObject ?: continue
                    fun d(key: String): Double =
                        (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                    val tiersJson = (o["tiers"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.mapNotNull { t ->
                            // A tier inherits the base price for every part it
                            // leaves out (catalog.go Price.UnmarshalJSON:
                            // or(t.Input, p.Input)) — a tier that lists only
                            // output still bills input at the base price, and
                            // reading it as 0 would undercharge the whole tier.
                            fun part(key: String, base: Double): Double =
                                (t[key] as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.content?.toDoubleOrNull() ?: base
                            val above = (t["above"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.content?.toLongOrNull() ?: 0L
                            if (above <= 0) return@mapNotNull null
                            MagpiePrice.TierPrice(
                                above = above,
                                input = part("input", d("input")),
                                output = part("output", d("output")),
                                cacheRead = part("cache_read", d("cache_read")),
                                cacheWrite = part("cache_write", d("cache_write")),
                                cacheWrite1h = part("cache_write_1h", 0.0),
                            )
                        }
                        ?.sortedBy { it.above }
                        ?: emptyList()
                    models[mid] = MagpiePrice(
                        input = d("input"), output = d("output"),
                        cacheRead = d("cache_read"), cacheWrite = d("cache_write"),
                        cacheWrite1h = d("cache_write_1h"),
                        tiers = tiersJson,
                    )
                }
                if (models.isNotEmpty()) out[pid.lowercase()] = models
            }
            val canon = HashMap<String, Map<String, String>>()
            (root["canonical"] as? JsonObject)?.forEach { (pid, perProvider) ->
                val inner = perProvider as? JsonObject ?: return@forEach
                val m = HashMap<String, String>(inner.size)
                inner.forEach { (mid, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { m[mid] = it }
                }
                if (m.isNotEmpty()) canon[pid.lowercase()] = m
            }
            return PriceTable(fetchedAt, out, canon)
        }
    }
}
