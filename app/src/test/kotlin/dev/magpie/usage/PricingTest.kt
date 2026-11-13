package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.domain.pricing.MagpiePrice
import dev.magpie.usage.domain.pricing.Peak
import dev.magpie.usage.domain.pricing.PriceLookup
import dev.magpie.usage.domain.pricing.PriceTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * 计费向量的两侧校验（期望值按上游 catalog.go/peak.go 的规则手推，
 * 与被测的 Kotlin 实现互相独立；调研报告 §7.5）。
 */
class PricingTest {

    // ---------------------------------------------------------------- 公式

    @Test
    fun `basic cost split at a flat price`() {
        val p = MagpiePrice(input = 0.5, output = 2.0, cacheRead = 0.1, cacheWrite = 1.25)
        // 1M in -> $0.50
        assertEquals(0.5, p.costSplit(1_000_000, 0, 0, 0, 0), 1e-12)
        // 1M in + 1M out + 0.5M cache read + 0.5M cache write
        assertEquals(0.5 + 2.0 + 0.05 + 0.625, p.costSplit(1_000_000, 1_000_000, 500_000, 500_000, 0), 1e-12)
    }

    @Test
    fun `tier applies to the whole request above its prompt size`() {
        val p = MagpiePrice(
            input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 1.0,
            tiers = listOf(MagpiePrice.TierPrice(above = 272_000, input = 2.0, output = 3.0, cacheRead = 1.0, cacheWrite = 2.0)),
        )
        // 272,001+ prompt tokens -> the whole request bills at the tier.
        val above = p.costSplit(300_000, 100_000, 0, 0, 0)
        assertEquals(300_000 * 2.0 / 1e6 + 100_000 * 3.0 / 1e6, above, 1e-12)
        // At or below -> base price.
        val below = p.costSplit(272_000, 100_000, 0, 0, 0)
        assertEquals(272_000 * 1.0 / 1e6 + 100_000 * 2.0 / 1e6, below, 1e-12)
        // Cached tokens count in the prompt (OpenAI's rule): the request
        // bills at the tier, cache read at the tier's cache price.
        val withCache = p.costSplit(100_000, 0, 200_000, 0, 0)
        assertEquals((100_000 * 2.0 + 200_000 * 1.0) / 1e6, withCache, 1e-12)
    }

    @Test
    fun `cache write 1h is clamped and bills at the 1h price`() {
        val p = MagpiePrice(input = 1.0, output = 1.0, cacheRead = 0.0, cacheWrite = 1.25, cacheWrite1h = 2.0)
        // 100k of the 120k writes were 1h: 20k at 1.25, 100k at 2.0.
        val cost = p.costSplit(0, 0, 0, 120_000, 100_000)
        assertEquals((20_000 * 1.25 + 100_000 * 2.0) / 1e6, cost, 1e-12)
        // A 1h figure over cache_write clamps to it.
        val clamped = p.costSplit(0, 0, 0, 100_000, 150_000)
        assertEquals(100_000 * 2.0 / 1e6, clamped, 1e-12)
    }

    @Test
    fun `claude models get 2x input for a 1h write when unlisted`() {
        val raw = MagpiePrice(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75)
        val fixed = MagpiePrice.withOneHourFor("claude-sonnet-5", raw)
        assertEquals(6.0, fixed.cacheWrite1h, 1e-12)
        // Non-claude keeps no 1h price; its writes bill at the 5-minute one.
        val other = MagpiePrice.withOneHourFor("gpt-x", raw)
        assertEquals(0.0, other.cacheWrite1h, 1e-12)
        assertEquals(raw.cacheWrite, other.oneHourForTest(), 1e-12)
    }

    // ---------------------------------------------------------------- 峰值

    private val ds = MagpiePrice(input = 0.5, output = 2.0, cacheRead = 0.1, cacheWrite = 0.25, peak = Peak.DEEPSEEK)

    @Test
    fun `deepseek peak doubles on weekday mornings utc`() {
        // 2026-11-11 是周三。
        val peak = ds.inEffect(Instant.parse("2026-11-11T02:00:00Z"))
        assertEquals(1.0, peak.input, 1e-12)
        val off = ds.inEffect(Instant.parse("2026-11-11T12:00:00Z"))
        assertEquals(0.5, off.input, 1e-12)
        // 周六无高峰。
        val saturday = ds.inEffect(Instant.parse("2026-11-14T02:00:00Z"))
        assertEquals(0.5, saturday.input, 1e-12)
        // 中国法定节假日（2026-10-01 国庆）无高峰。
        val holiday = ds.inEffect(Instant.parse("2026-10-01T02:00:00Z"))
        assertEquals(0.5, holiday.input, 1e-12)
    }

    @Test
    fun `ollama peak only in its own window`() {
        val o = MagpiePrice(input = 0.5, output = 1.0, cacheRead = 0.0, cacheWrite = 0.0, peak = Peak.OLLAMA)
        assertEquals(1.0, o.inEffect(Instant.parse("2026-11-11T13:00:00Z")).input, 1e-12)
        assertEquals(0.5, o.inEffect(Instant.parse("2026-11-11T11:00:00Z")).input, 1e-12)
        // DEEPSEEK 的时段对 OLLAMA 不生效的方向也成立：6-10 点不在 12-18。
        assertEquals(0.5, o.inEffect(Instant.parse("2026-11-11T07:00:00Z")).input, 1e-12)
    }

    // ---------------------------------------------------------------- 查找链

    private val table = PriceTable.parse(
        """
        {"fetchedAt":"2026-11-13","providers":{
          "openai":{"gpt-x":{"input":1.0,"output":2.0}},
          "deepseek":{"DeepSeek-V4.1":{"input":0.4,"output":1.2}},
          "openrouter":{"vendor/gpt-x":{"input":5.0,"output":6.0}}
        }}
        """.trimIndent(),
    )

    private fun row(
        provider: String,
        model: String,
        served: String = "",
        sessionProvider: String = "",
    ): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d",
        t = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli(),
        agent = "a", provider = provider, model = model, requested = "", served = served,
        inTok = 1_000_000, outTok = 0, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
        cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
        status = 200, err = "", errType = "", rid = "", session = "", sessionProvider = sessionProvider,
        source = "", tries = 0, handedOn = false, rejected = false, costUsd = null, credits = null,
        upBytes = 0, downBytes = 0, raw = "",
    )

    @Test
    fun `provider and model exact lookup`() {
        val lookup = PriceLookup(table)
        assertEquals(1.0, lookup.catalogCostOf(row("openai", "gpt-x"))!!, 1e-12)
    }

    @Test
    fun `maker fallback finds the model at its maker`() {
        val lookup = PriceLookup(table)
        // "gemini" 不在表里，但 gpt-x 的厂商牌价在 openai 名下。
        assertEquals(1.0, lookup.catalogCostOf(row("gemini", "gpt-x"))!!, 1e-12)
        // 厂商牌价优先于别的供应商转售价：DeepSeek-V4.1 在 "deepseek" 名下
        // （该行 01:00 UTC 是 DeepSeek 高峰，牌价 ×2）。
        assertEquals(0.8, lookup.catalogCostOf(row("some-relay", "DeepSeek-V4.1"))!!, 1e-12)
    }

    @Test
    fun `served model falls back when the asked model is unpriced`() {
        val lookup = PriceLookup(table)
        // asked-new 无价 → 按 served 的 gpt-x：1M input × $1.0/M。
        assertEquals(1.0, lookup.catalogCostOf(row("openai", "asked-new", served = "gpt-x"))!!, 1e-12)
    }

    @Test
    fun `session provider prices a session-unknown row`() {
        val lookup = PriceLookup(table)
        // A session row prices at its maker first (BasePriceIn's !known
        // branch): "vendor/gpt-x" is gpt-x, which openai lists.
        assertEquals(
            1.0,
            lookup.catalogCostOf(row("session-unknown", "vendor/gpt-x", sessionProvider = "openrouter"))!!,
            1e-12,
        )
        // The session provider answers only when nothing prices the model:
        // openrouter lists vendor/gpt-x at $5, and no maker does.
        assertEquals(
            5.0,
            lookup.catalogCostOf(row("session-unknown", "openrouter/vendor/gpt-x", sessionProvider = "openrouter"))!!,
            1e-12,
        )
        // 目录没列的依然不定价。
        assertNull(lookup.catalogCostOf(row("session-unknown", "nope", sessionProvider = "openrouter")))
    }

    @Test
    fun `unknown stays null`() {
        val lookup = PriceLookup(table)
        assertNull(lookup.catalogCostOf(row("no-provider", "no-model")))
    }

    @Test
    fun `an updated price table keeps canonical_model_id`() {
        // 「更新价格表」走 PriceTableStore.trim：它必须保留 canonical_model_id，
        // 否则更新后厂商识别失效，费用会退回转售商价格（内置快照保留该字段）。
        val trimmed = dev.magpie.usage.data.PriceTableStore.trim(
            """
            {"deepseek":{"models":{
               "deepseek-flash":{"cost":{"input":0.15,"output":0.6},"canonical_model_id":"deepseek/deepseek-v4.1-flash-1017"},
               "deepseek-v4-pro":{"cost":{"input":0.66,"output":1.98}}
             }},
             "above":{"models":{
               "deepseek-v4.1-flash":{"cost":{"input":0.165,"output":0.66},"canonical_model_id":"deepseek/deepseek-v4.1-flash"}
             }}}
            """.trimIndent(),
        )
        val t = PriceTable.parse(trimmed)
        // 厂商价（deepseek 的 deepseek-flash）必须胜出转售商 above。
        val price = t.makerPrice("deepseek-v4.1-flash")!!
        assertEquals(0.15, price.input, 1e-12)
    }

    // ------------------------------------------------- 厂商识别（canonical）

    /** models.dev's canonical_model_id: who an entry says the model is. */
    private val canonicalTable = PriceTable.parse(
        """
        {"fetchedAt":"2026-11-13",
         "providers":{
           "deepseek":{"deepseek-flash":{"input":0.15,"output":0.6,"cache_read":0.003}},
           "mistral":{"deepseek-v4.1-flash-1017":{"input":0.3,"output":1.2}},
           "above":{"deepseek-v4.1-flash":{"input":0.165,"output":0.66}},
           "sap-ai-core":{"anthropic--claude-4.7-opus":{"input":5.0,"output":25.0}},
           "anthropic":{"claude-opus-4-7":{"input":15.0,"output":75.0}}
         },
         "canonical":{
           "deepseek":{"deepseek-flash":"deepseek/deepseek-v4.1-flash-1017"},
           "mistral":{"deepseek-v4.1-flash-1017":"deepseek/deepseek-v4.1-flash-1017"},
           "above":{"deepseek-v4.1-flash":"deepseek/deepseek-v4.1-flash"},
           "sap-ai-core":{"anthropic--claude-4.7-opus":"anthropic/claude-opus-4-7"},
           "anthropic":{"claude-opus-4-7":"anthropic/claude-opus-4-7"}
         }}
        """.trimIndent(),
    )

    @Test
    fun `the maker's own price beats a reseller that lists the id first`() {
        // DeepSeek 自己只列 deepseek-flash（canonical 指向 v4.1-flash），
        // 转售商 above 直接列 deepseek-v4.1-flash 更便宜/更贵都不算厂商价。
        // 该行时间 01:00 UTC 是 DeepSeek 的高峰时段，牌价 ×2。
        val lookup = PriceLookup(canonicalTable)
        val cost = lookup.catalogCostOf(row("some-agent", "deepseek-v4.1-flash"))!!
        assertEquals("DeepSeek 官方 $0.15/M × 高峰 2 = $0.30/M", 0.30, cost, 1e-12)
    }

    @Test
    fun `a snapshot-dated canonical still finds the maker`() {
        // deepseek-v4.1-flash-1017 由 Mistral 转售 $0.30；其 canonical 的
        // 日期去掉后就是 DeepSeek 的 deepseek-flash 那一条（高峰 ×2）。
        val lookup = PriceLookup(canonicalTable)
        assertEquals(0.30, lookup.catalogCostOf(row("relay", "deepseek-v4.1-flash-1017"))!!, 1e-12)
    }

    @Test
    fun `a reseller of another maker's model is never read as the maker`() {
        // sap-ai-core 转售 Anthropic 的模型；claude 的价格必须是 Anthropic 的。
        val lookup = PriceLookup(canonicalTable)
        val cost = lookup.catalogCostOf(row("some-agent", "claude-opus-4-7"))!!
        assertEquals("Anthropic 的 $15/M，不是 SAP 的 $5/M", 15.0, cost, 1e-12)
    }

    @Test
    fun `a maker with no canonical is still read at its own price`() {
        // Anthropic 自己的 claude-opus-4-7 没有 canonical_model_id，而 302ai
        // 转售它（canonical 指向 anthropic）。厂商自己的价必须优先。
        val t = PriceTable.parse(
            """
            {"fetchedAt":"2026-11-13",
             "providers":{
               "anthropic":{"claude-opus-4-7":{"input":15.0,"output":75.0}},
               "302ai":{"claude-opus-4-7":{"input":5.0,"output":25.0}}
             },
             "canonical":{"302ai":{"claude-opus-4-7":"anthropic/claude-opus-4-7"}}}
            """.trimIndent(),
        )
        val p = t.makerPrice("claude-opus-4-7")!!
        assertEquals("Anthropic 官方 $15/M，不是 302ai 的 $5/M", 15.0, p.input, 1e-12)
    }

    @Test
    fun `a tier inherits the base price for the parts it leaves out`() {        // models.dev 的分档常只写变了的字段；缺的按基础价算，读成 0 会把
        // 整个高档区间严重低估（catalog.go Price.UnmarshalJSON 的 or()）。
        val t = PriceTable.parse(
            """
            {"fetchedAt":"2026-11-13","providers":{"p":{"m":{
              "input":1.0,"output":2.0,"cache_read":0.1,
              "tiers":[{"above":1000,"output":4.0}]
            }}}}
            """.trimIndent(),
        )
        val p = t.priceOf("p", "m")!!
        // 2000-token prompt 进档：input 仍是基础 1.0，output 是档内 4.0。
        assertEquals((2000 * 1.0 + 1000 * 4.0) / 1e6, p.costSplit(2000, 1000, 0, 0, 0), 1e-12)
    }
}

private fun MagpiePrice.oneHourForTest(): Double = if (cacheWrite1h > 0) cacheWrite1h else cacheWrite
