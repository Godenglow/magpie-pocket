package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.data.OrganizeState
import dev.magpie.usage.data.applyOrganize
import dev.magpie.usage.domain.UsageAggregator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 概览的供应商→账号用量拆分与整理（顺序/隐藏）。 */
class OverviewOrganizeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun row(
        provider: String,
        host: String = "",
        account: String = "",
        inTok: Long = 100,
        outTok: Long = 0,
        status: Int = 200,
        err: String = "",
    ): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d",
        t = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli(),
        agent = "a", provider = provider, model = "m", requested = "", served = "",
        inTok = inTok, outTok = outTok, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
        cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
        status = status, err = err, errType = "", rid = "", session = "", sessionProvider = "",
        host = host, providerAccount = account,
        source = "", tries = 0, handedOn = false, rejected = false, costUsd = null, credits = null,
        upBytes = 0, downBytes = 0, raw = "",
    )

    @Test
    fun `provider totals equal the sum of its accounts`() {
        val rows = listOf(
            row("workbuddy", account = "Goldenglow", inTok = 300),
            row("workbuddy", host = "api.x.com as 拉布拉路", inTok = 200),
            row("zcode", account = "me", inTok = 50),
            row("workbuddy", account = "Goldenglow", inTok = 100, status = 500, err = "boom"),
        )
        val shares = UsageAggregator.byProviderAccount(
            rows, UsageAggregator.Range.ALL, zone,
            Instant.parse("2026-11-13T02:00:00Z"),
        )
        // 供应商按总量降序：workbuddy(600) 在前。
        assertEquals(listOf("workbuddy", "zcode"), shares.map { it.provider })
        val wb = shares[0]
        assertEquals(600L, wb.totals.allTokens)
        // 失败行也是一次请求（上游 count 规则）。
        assertEquals(3L, wb.totals.calls)
        assertEquals(1L, wb.totals.errors)
        // 账号按用量降序：Goldenglow(400) > 拉布拉路(200)。
        assertEquals(listOf("Goldenglow", "拉布拉路"), wb.accounts.map { it.account })
        assertEquals(400L, wb.accounts[0].totals.allTokens)
        // 供应商总量 = 账号之和。
        assertEquals(wb.totals.allTokens, wb.accounts.sumOf { it.totals.allTokens })
    }

    @Test
    fun `rejected rows and out-of-window rows are excluded`() {
        val rows = listOf(
            row("p", account = "a", inTok = 100),
            row("p", account = "a", inTok = 999).copy(rejected = true, status = 400),
        )
        val shares = UsageAggregator.byProviderAccount(
            rows, UsageAggregator.Range.TODAY, zone,
            Instant.parse("2026-11-13T02:00:00Z"),
        )
        assertEquals(100L, shares.single().totals.allTokens)
        assertEquals(1L, shares.single().totals.calls)
    }

    @Test
    fun `provider filter applies`() {
        val rows = listOf(row("keep", inTok = 10), row("drop", inTok = 20))
        val shares = UsageAggregator.byProviderAccount(
            rows, UsageAggregator.Range.ALL, zone,
            Instant.parse("2026-11-13T02:00:00Z"),
            providerFilter = setOf("keep"),
        )
        assertEquals(listOf("keep"), shares.map { it.provider })
    }

    @Test
    fun `organize reorders and hides without touching the rest`() {
        val shares = listOf(
            UsageAggregator.ProviderUsageShare("a", UsageAggregator.Totals(), emptyList()),
            UsageAggregator.ProviderUsageShare("b", UsageAggregator.Totals(), emptyList()),
            UsageAggregator.ProviderUsageShare("c", UsageAggregator.Totals(), emptyList()),
        )
        // 自定义顺序 c,b；a 是新供应商 → 排在其后；隐藏 b。
        val organized = shares.applyOrganize(
            OrganizeState(order = listOf("c", "b"), hidden = setOf("b")),
        )
        assertEquals(listOf("c", "a"), organized.map { it.provider })
        // 空整理 = 原顺序。
        assertEquals(listOf("a", "b", "c"), shares.applyOrganize(OrganizeState()).map { it.provider })
    }
}
