package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.domain.UsageHeatmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageHeatmapTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-11-13 是周五；本周周一是 2026-11-09。
    private val today = LocalDate.of(2026, 11, 13)

    private fun row(t: Instant, provider: String = "p", inTok: Long = 100, rejected: Boolean = false): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d", t = t.toEpochMilli(),
        agent = "a", provider = provider, model = "m", requested = "", served = "",
        inTok = inTok, outTok = 0, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
        cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
        status = if (rejected) 400 else 200, err = "", errType = "", rid = "", session = "", source = "",
        tries = 0, handedOn = false, rejected = rejected, costUsd = null, credits = null,
        upBytes = 0, downBytes = 0, raw = "",
    )

    @Test
    fun `53 columns ending with the week containing today, monday-aligned`() {
        val heatmap = UsageHeatmap.build(emptyList(), zone, today)
        assertEquals(53, heatmap.weeks.size)
        val last = heatmap.weeks.last()
        assertEquals(LocalDate.of(2026, 11, 9), last.monday)
        assertEquals(today, last.days[4].date) // 周五
        assertTrue(heatmap.weeks.all { it.days.size == 7 })
        assertEquals(today.plusDays(2), last.days[6].date) // 未来两天保留在网格里但为空
    }

    @Test
    fun `rows aggregate per local day`() {
        // 11-12 22:00Z = 11-13 06:00 CST；11-12 14:00Z = 11-12 22:00 CST。
        val rows = listOf(
            row(Instant.parse("2026-11-12T22:00:00Z"), inTok = 100),
            row(Instant.parse("2026-11-12T14:00:00Z"), inTok = 50),
        )
        val heatmap = UsageHeatmap.build(rows, zone, today)
        val day13 = heatmap.weeks.last().days[4] // 11-13
        val day12 = heatmap.weeks.last().days[3] // 11-12
        assertEquals(100L, day13.totals.allTokens)
        assertEquals(50L, day12.totals.allTokens)
    }

    @Test
    fun `provider filter applies and rejected rows stay out`() {
        val rows = listOf(
            row(Instant.parse("2026-11-13T01:00:00Z"), provider = "openai", inTok = 100),
            row(Instant.parse("2026-11-13T02:00:00Z"), provider = "openai", inTok = 40),
            row(Instant.parse("2026-11-13T03:00:00Z"), provider = "anthropic", inTok = 999),
            row(Instant.parse("2026-11-13T04:00:00Z"), provider = "x", inTok = 7, rejected = true),
        )
        val filtered = UsageHeatmap.build(rows, zone, today, providerFilter = setOf("openai"))
        assertEquals(140L, filtered.weeks.last().days[4].totals.allTokens)
    }

    @Test
    fun `levels scale with sqrt and zero stays level 0`() {
        assertEquals(0, UsageHeatmap.level(0.0, 100.0))
        assertEquals(4, UsageHeatmap.level(100.0, 100.0))
        assertEquals(1, UsageHeatmap.level(1.0, 100.0))
        assertEquals(2, UsageHeatmap.level(25.0, 100.0))
        assertEquals(3, UsageHeatmap.level(30.0, 100.0))
        assertEquals(4, UsageHeatmap.level(64.0, 100.0))
        // 无最大值时全部为 0。
        assertEquals(0, UsageHeatmap.level(50.0, 0.0))
    }

    @Test
    fun `month labels appear when a column starts a new month`() {
        val heatmap = UsageHeatmap.build(emptyList(), zone, today)
        // 从 2025-11-10 到 2026-11-09 共 53 列：每进入一个新月份，该列有标签，
        // 与桌面一致（两端的 11 月都会标出）。
        val labels = heatmap.monthLabels
        assertTrue(labels.containsValue("11月"))
        assertTrue(labels.containsValue("10月"))
        assertTrue(labels.containsValue("12月"))
        assertEquals(12, labels.size)
    }

    @Test
    fun `coverage reports what the phone actually holds`() {
        val heatmap = UsageHeatmap.build(
            listOf(row(Instant.parse("2026-08-16T04:00:00Z"), inTok = 1)),
            zone, today,
        )
        assertEquals(LocalDate.of(2026, 8, 16), heatmap.firstDataDate)
        assertEquals(LocalDate.of(2026, 8, 16), heatmap.lastDataDate)
    }
}
