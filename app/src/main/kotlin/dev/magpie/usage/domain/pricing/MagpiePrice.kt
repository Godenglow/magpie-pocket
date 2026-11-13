package dev.magpie.usage.domain.pricing

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * A model's list price, USD per million tokens — the phone-side port of
 * yetone/magpie @ 6acf5ee5 internal/catalog Price (catalog.go:262-420) and
 * Peak (catalog_peak.go).
 *
 * models.dev lists DeepSeek/Ollama Cloud's OFF-PEAK price as the price; a
 * call inside [Peak]'s hours bills at [PEAK_FACTOR] times it.
 */
data class MagpiePrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val cacheWrite1h: Double = 0.0,
    val tiers: List<TierPrice> = emptyList(),
    val peak: Peak = Peak.NONE,
) {

    data class TierPrice(
        val above: Long,
        val input: Double,
        val output: Double,
        val cacheRead: Double,
        val cacheWrite: Double,
        val cacheWrite1h: Double = 0.0,
    )

    /** x2 in peak hours (DeepSeek: "off-peak rates are half of the peak rates"). */
    fun times(r: Double): MagpiePrice = copy(
        input = input * r,
        output = output * r,
        cacheRead = cacheRead * r,
        cacheWrite = cacheWrite * r,
        cacheWrite1h = cacheWrite1h * r,
        tiers = tiers.map {
            it.copy(
                input = it.input * r,
                output = it.output * r,
                cacheRead = it.cacheRead * r,
                cacheWrite = it.cacheWrite * r,
                cacheWrite1h = it.cacheWrite1h * r,
            )
        },
    )

    /**
     * In is the price a call made at [at] is billed at: PeakFactor times this
     * one in its peak hours, else this one (catalog_peak.go:61).
     */
    fun inEffect(at: Instant): MagpiePrice =
        if (peak.at(at)) times(PEAK_FACTOR.toDouble()) else this

    /**
     * At is the flat price the whole request is billed at: the highest tier
     * its prompt (input + cache_read + cache_write) is over, else the base
     * (catalog.go:385 — OpenAI counts cached tokens in the prompt and bills
     * the whole request at the tier).
     */
    private fun at(prompt: Long): MagpiePrice {
        var flat = this
        for (t in tiers) {
            if (prompt > t.above) {
                flat = MagpiePrice(
                    input = t.input, output = t.output,
                    cacheRead = t.cacheRead, cacheWrite = t.cacheWrite,
                    cacheWrite1h = t.cacheWrite1h, peak = peak,
                )
            }
        }
        return flat
    }

    /** OneHour: the 1h price when given, else the 5-minute one (catalog.go:397). */
    private fun oneHour(): Double = if (cacheWrite1h > 0) cacheWrite1h else cacheWrite

    /**
     * CostSplit — catalog.go:414. cacheWrite1h of the cacheWrite tokens were
     * kept for an hour and bill at that price; the whole call is billed at
     * the tier its input reaches. Reasoning tokens bill as output and are
     * already inside the output count.
     */
    fun costSplit(
        input: Long,
        output: Long,
        cacheRead: Long,
        cacheWrite: Long,
        cacheWrite1h: Long,
    ): Double {
        val oneHourTokens = cacheWrite1h.coerceIn(0, cacheWrite.coerceAtLeast(0))
        val f = at(input + cacheRead + cacheWrite)
        return (input * f.input + output * f.output +
            cacheRead * f.cacheRead +
            (cacheWrite - oneHourTokens) * f.cacheWrite +
            oneHourTokens * f.oneHour()) / 1e6
    }

    companion object {
        const val PEAK_FACTOR = 2

        /**
         * OneHourFor (catalog.go:354): models.dev lists no 1h cache write; a
         * Claude model with a (5-minute) cache write price bills a 1h write
         * at 2× input. Any other model's writes stay at the 5-minute price.
         */
        fun withOneHourFor(modelId: String, p: MagpiePrice): MagpiePrice {
            if (p.cacheWrite <= 0 || p.cacheWrite1h > 0) return p
            if (!modelId.lowercase().contains("claude")) return p
            return p.copy(
                cacheWrite1h = 2 * p.input,
                tiers = p.tiers.map { t ->
                    if (t.cacheWrite1h <= 0) t.copy(cacheWrite1h = 2 * t.input) else t
                },
            )
        }
    }
}

/** Peak hours per vendor — catalog_peak.go:14-30 and provider/peak.go. */
enum class Peak {
    NONE,
    /** DeepSeek's own API: 01:00–04:00 & 06:00–10:00 UTC, Mon–Fri, not a CN holiday. */
    DEEPSEEK,
    /** Command Code / OpenCode Go: the same hours, Mon–Fri, holidays included. */
    WEEKDAYS,
    /** Ollama Cloud: 12:00–18:00 UTC, Mon–Fri, two DeepSeek models. */
    OLLAMA,
    ;

    /** At is whether a call made at [at] is in the peak hours (catalog_peak.go:39). */
    fun at(at: Instant): Boolean {
        if (this == NONE) return false
        val u = at.atZone(ZoneOffset.UTC)
        if (u.dayOfWeek == DayOfWeek.SATURDAY || u.dayOfWeek == DayOfWeek.SUNDAY) return false
        val h = u.hour
        val inWindow = when (this) {
            OLLAMA -> h >= 12 && h < 18
            DEEPSEEK, WEEKDAYS -> (h in 1..3 || h in 6..9)
            NONE -> return false
        }
        if (!inWindow) return false
        // DeepSeek's peak is China's working hours, so a holiday's date is
        // the same read in China time.
        if (this == DEEPSEEK && cnHoliday(u.toInstant())) return false
        return true
    }

    companion object {
        fun of(id: String?): Peak = when (id) {
            "deepseek" -> DEEPSEEK
            "weekdays" -> WEEKDAYS
            "ollama" -> OLLAMA
            else -> NONE
        }

        /**
         * PeakOf (provider_peak.go:59): which vendors bill by the hour is told
         * by the host their base URLs point at, and only a provider with none
         * (a plugin's) by its preset or id. The models listed are the ones
         * each vendor bills so, without a maker's prefix; Ollama's also match
         * with a ":tag" after them.
         */
        private val PEAK_BILLS: List<Pair<(String) -> Boolean, Pair<List<String>, Peak>>> = listOf(
            // DeepSeek's own API, every model.
            ({ host: String -> host == "api.deepseek.com" }) to (emptyList<String>() to DEEPSEEK),
            // Command Code's DeepSeek V4 models.
            ({ host: String -> host == "api.commandcode.ai" }) to (
                listOf(
                    "deepseek-v4.1-flash", "deepseek-v4.1-flash-fast",
                    "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro",
                ) to WEEKDAYS
                ),
            // OpenCode Go's.
            ({ host: String -> host.endsWith("opencode.ai") }) to (
                listOf("deepseek-v4.1-flash", "deepseek-v4-pro") to WEEKDAYS
                ),
            // Ollama Cloud's two Off-Peak rows.
            ({ host: String -> host == "ollama.com" }) to (
                listOf("deepseek-v4.1-flash", "deepseek-v4-pro") to OLLAMA
                ),
        )

        /**
         * The peak hours a call to [model] at the provider whose base URLs are
         * [baseUrls] is billed in. Empty when the vendor bills one price at
         * every hour, or the model is not one of its hourly-billed ones.
         */
        fun forCall(providerId: String, model: String, baseUrls: List<String>): Peak {
            val m = model.trim().lowercase().substringAfterLast('/')
            val hosts = baseUrls.mapNotNull { hostOf(it) }.filter { it.isNotEmpty() }
            for ((at, spec) in PEAK_BILLS) {
                val (models, peak) = spec
                if (models.isNotEmpty() && m !in models && m.substringBefore(':') !in models) continue
                val matchesVendor = hosts.isNotEmpty() && hosts.any { at(it) }
                if (matchesVendor) return peak
            }
            // A provider with no base URL of its own (a plugin's, one removed
            // since) is told by its preset or id.
            if (hosts.isEmpty()) {
                return when (providerId.lowercase()) {
                    "deepseek" -> DEEPSEEK
                    "commandcode", "opencode-go" -> WEEKDAYS
                    "ollama-cloud" -> OLLAMA
                    else -> NONE
                }
            }
            return NONE
        }

        private fun hostOf(url: String): String? = try {
            java.net.URI(url.trim()).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * China's public-holiday days off, as the State Council lists them — the
 * table catalog_peak.go carries (2026), kept for DeepSeek's peak hours. A
 * year not listed counts no holiday, so its weekdays price at peak: the
 * most a call could have cost.
 */
private val CN_HOLIDAYS: Map<Int, Set<String>> = mapOf(
    2026 to setOf(
        "01-01", "01-02", "01-03",
        "02-15", "02-16", "02-17", "02-18", "02-19", "02-20", "02-21", "02-22", "02-23",
        "04-04", "04-05", "04-06",
        "05-01", "05-02", "05-03", "05-04", "05-05",
        "06-19", "06-20", "06-21",
        "09-25", "09-26", "09-27",
        "10-01", "10-02", "10-03", "10-04", "10-05", "10-06", "10-07",
    ),
)

private fun cnHoliday(at: Instant): Boolean {
    val cn = at.atZone(ZoneOffset.ofHours(8))
    val md = "%02d-%02d".format(cn.monthValue, cn.dayOfMonth)
    return CN_HOLIDAYS[cn.year]?.contains(md) == true
}
