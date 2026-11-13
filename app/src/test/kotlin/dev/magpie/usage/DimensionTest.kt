package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.domain.UsageAggregator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 拆分维度：供应商 / 模型 / 模型·供应商 / Agent 的分组与展示名。 */
class DimensionTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-13T02:00:00Z")

    private fun row(provider: String, model: String, agent: String, inTok: Long): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d",
        t = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli(),
        agent = agent, provider = provider, model = model, requested = "", served = "",
        inTok = inTok, outTok = 0, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
        cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
        status = 200, err = "", errType = "", rid = "", session = "", source = "",
        tries = 0, handedOn = false, rejected = false, costUsd = null, credits = null,
        upBytes = 0, downBytes = 0, raw = "",
    )

    private val rows = listOf(
        row("openai", "gpt-x", "codex", 100),
        row("openai", "gpt-x", "codex", 40),
        row("anthropic", "claude-x", "claude-code", 30),
    )

    @Test
    fun `provider dimension groups by provider`() {
        val r = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, now, dimension = UsageAggregator.Dimension.PROVIDER)
        assertEquals(listOf("openai", "anthropic"), r.providers.map { it.id })
        assertEquals(140L, r.providers[0].totals.allTokens)
        // 图表各桶的分组键同样是供应商。
        assertEquals(setOf("openai", "anthropic"), r.series.single().providers.keys)
    }

    @Test
    fun `model dimension groups by model only`() {
        val r = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, now, dimension = UsageAggregator.Dimension.MODEL)
        assertEquals(listOf("gpt-x", "claude-x"), r.providers.map { it.id })
        assertEquals("gpt-x", UsageAggregator.Dimension.MODEL.display("gpt-x"))
        assertEquals("未知模型", UsageAggregator.Dimension.MODEL.display(""))
    }

    @Test
    fun `model-provider dimension splits a model across providers`() {
        val rows2 = rows + row("deepseek", "gpt-x", "codex", 7)
        val r = UsageAggregator.summarize(rows2, UsageAggregator.Range.ALL, zone, now, dimension = UsageAggregator.Dimension.MODEL_PROVIDER)
        val ids = r.providers.map { it.id }
        assertEquals(setOf("openai/gpt-x", "anthropic/claude-x", "deepseek/gpt-x"), ids.toSet())
        assertEquals("gpt-x · openai", UsageAggregator.Dimension.MODEL_PROVIDER.display("openai/gpt-x"))
        // 同一模型在两个供应商下是两组。
        assertEquals(140L, r.providers.first { it.id == "openai/gpt-x" }.totals.allTokens)
        assertEquals(7L, r.providers.first { it.id == "deepseek/gpt-x" }.totals.allTokens)
        // 配色跟随模型部分。
        assertEquals(
            UsageAggregator.Dimension.MODEL_PROVIDER.colorKey("openai/gpt-x"),
            UsageAggregator.Dimension.MODEL_PROVIDER.colorKey("deepseek/gpt-x"),
        )
    }

    @Test
    fun `agent dimension groups by agent`() {
        val r = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, now, dimension = UsageAggregator.Dimension.AGENT)
        assertEquals(setOf("codex", "claude-code"), r.providers.map { it.id }.toSet())
        assertEquals(140L, r.providers.first { it.id == "codex" }.totals.allTokens)
        assertEquals("未知来源", UsageAggregator.Dimension.AGENT.display(""))
    }

    @Test
    fun `provider filter still applies in every dimension`() {
        UsageAggregator.Dimension.entries.forEach { dim ->
            val r = UsageAggregator.summarize(
                rows, UsageAggregator.Range.ALL, zone, now,
                providerFilter = setOf("openai"), dimension = dim,
            )
            assertEquals(1, r.providers.size)
            assertEquals(140L, r.totals.allTokens)
        }
    }
}
