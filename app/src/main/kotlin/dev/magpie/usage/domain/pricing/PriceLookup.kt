package dev.magpie.usage.domain.pricing

import dev.magpie.usage.core.model.CallRow
import java.time.Instant

/**
 * Resolves a record's catalog price and cost, following the desktop's
 * ledger.Pricer chain in the form the record fields alone support
 * (upstream @ 6acf5ee5, ledger.go:585-656 and prov_models.go tariffOf):
 *
 *   explicit cost (OwnCost)  — handled by the caller/aggregator first;
 *   base(provider, served)   — when the reply named another model;
 *   base(provider, model)    — the asked-for model;
 *   base(provider, served)   — the fallback when the model is unpriced;
 *   priceOf(sessionProvider, model) — session-file rows (session-unknown).
 *
 * base(p, m) = table.priceOf(p, m) ?: table.makerPrice(m) — the desktop's
 * ListPrice, which looks at the provider's own catalogue first and only then
 * at the model's maker (tariffOf). A record's own time picks the peak
 * multiplier (CostAt -> Price.In).
 */
class PriceLookup(private val table: PriceTable) {

    private val memo = HashMap<String, MagpiePrice?>()

    fun priceFor(r: CallRow): MagpiePrice? {
        val key = "${r.provider}|${r.model}|${r.served}|${r.sessionProvider}"
        synchronized(memo) {
            if (memo.containsKey(key)) return memo[key]
            val price = resolve(r)
            memo[key] = price
            return price
        }
    }

    /**
     * The call's cost in USD, or null when no price is known (the row is
     * then Unpriced). An explicit cost on the record is the caller's to
     * prefer — the aggregator does.
     */
    fun catalogCostOf(r: CallRow): Double? {
        val price = priceFor(r) ?: return null
        val effective = price.inEffect(Instant.ofEpochMilli(r.t))
        return effective.costSplit(r.inTok, r.outTok, r.cacheRead, r.cacheWrite, r.cacheWrite1h)
    }

    /** The provider's own list price, else the model's maker's (tariffOf). */
    private fun base(providerId: String, modelId: String): MagpiePrice? {
        if (providerId.isEmpty() || modelId.isEmpty()) return null
        return table.priceOf(providerId, modelId) ?: table.makerPrice(modelId)
    }

    private fun resolve(r: CallRow): MagpiePrice? {
        val served = r.served.trim()
        val asked = r.model
        val swapped = served.isNotEmpty() && served != asked && !sameModel(asked, served)

        // ledger.go:609-641 — the desktop's order, exactly:
        //   swapped ? (base(asked) ?: base(served)) : base(asked)
        //   then base(served) when nothing above priced it,
        //   then, for a session-file row, PriceOf(sessionProvider, model).
        // base(p, m) is BasePriceIn: the provider's own list price, else the
        // model's maker's (tariffOf's !known branch). A session-file row's
        // billing provider is the unknown one, which no catalogue lists, so
        // BasePriceIn falls through to the maker — that is how the desktop
        // prices a local session's calls; skipping this fallback would leave
        // otherwise known models unpriced.
        if (swapped) {
            base(r.provider, asked)?.let { return it }
            base(r.provider, served)?.let { return it }
        } else {
            base(r.provider, asked)?.let { return it }
        }
        if (served.isNotEmpty()) {
            base(r.provider, served)?.let { return it }
        }
        // A session file's usage entry names the route the call went through.
        // The name is the file's word for the route, not an account of ours,
        // so it prices only what the catalogue itself lists under it — this
        // very model id, at that provider, at a price (ledger.go:632-649).
        if (r.provider == CallRow.UNKNOWN_PROVIDER && r.sessionProvider.isNotEmpty()) {
            return table.priceOf(r.sessionProvider, r.model)
        }
        return null
    }

    /**
     * ListedAs: whether the provider lists served as another name of the
     * asked-for model, so the reply did not swap models. The phone only has
     * the ids, so a bare-id match (path, case, dots-vs-dashes, a snapshot
     * date, a ":tag") counts as the same model.
     */
    private fun sameModel(asked: String, served: String): Boolean {
        fun norm(id: String): String = id.substringAfterLast('/').lowercase()
            .replace('.', '-')
            .replace(SNAPSHOT_DATE, "")
            .substringBefore(':')
        return norm(asked) == norm(served)
    }

    private companion object {
        val SNAPSHOT_DATE = Regex("-\\d{4}$")
    }
}
