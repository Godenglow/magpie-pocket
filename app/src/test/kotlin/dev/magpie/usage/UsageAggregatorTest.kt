package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.domain.UsageAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 验收口径 3-11 的可自动化部分。期望值来自 Python 生成器里按上游规则
 * 手工推导的结果，与被测的 Kotlin 聚合器互相独立。
 */
class UsageAggregatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val pack by lazy {
        Fixtures.open(Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage"))
    }

    private fun expected(name: String): Map<String, Any?> =
        Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage").expected

    @Test
    fun `fixture1 matches the hand-computed upstream totals`() {
        // "now" is during 2026-03-31 evening CST, so the whole day is "today".
        val now = Instant.parse("2026-03-31T12:00:00Z")
        val result = UsageAggregator.summarize(pack.calls, UsageAggregator.Range.ALL, zone, now)
        val e = expected("fixture1")
        val t = result.totals
        assertEquals((e["calls"] as Number).toLong(), t.calls)
        assertEquals((e["errors"] as Number).toLong(), t.errors)
        assertEquals((e["retried"] as Number).toLong(), t.retried)
        assertEquals((e["canceled"] as Number).toLong(), t.canceled)
        assertEquals((e["allTokens"] as Number).toLong(), t.allTokens)
        assertEquals((e["input"] as Number).toLong(), t.input)
        assertEquals((e["output"] as Number).toLong(), t.output)
        assertEquals((e["cacheRead"] as Number).toLong(), t.cacheRead)
        assertEquals((e["cacheWrite"] as Number).toLong(), t.cacheWrite)
        assertEquals((e["costUsd"] as Number).toDouble(), t.costUsd, 1e-9)
        assertEquals((e["unpriced"] as Number).toLong(), t.unpriced)
        assertEquals((e["cacheUnknownCalls"] as Number).toLong(), t.cacheUnknownCalls)
        assertEquals((e["cacheUnknownTokens"] as Number).toLong(), t.cacheUnknown)
    }

    @Test
    fun `acceptance 3 - 4200 tokens and 75 percent hit rate on the reference sample`() {
        val row = pack.calls.first()
        assertEquals(4200L, row.allTokens)
        // The reference sample in doc 03: in=1000 out=200 cache_read=3000 -> 75%.
        val totals = UsageAggregator.Totals()
        totals.add(row, row.costUsd)
        assertEquals(4200L, totals.allTokens)
        assertEquals(0.75, totals.hitRate()!!, 1e-12)
        // This row carries an explicit price; an identical row without one
        // counts as unpriced instead (no fake amounts).
        assertEquals(0L, totals.unpriced)
        assertEquals(0.012, totals.costUsd, 1e-12)
        val unpricedRow = row.copy(costUsd = null)
        val t2 = UsageAggregator.Totals()
        t2.add(unpricedRow, null)
        assertEquals(1L, t2.unpriced)
        assertEquals(0.0, t2.costUsd, 1e-12)
    }

    @Test
    fun `acceptance 4 - cache_unknown never pollutes the denominator`() {
        val now = Instant.parse("2026-03-31T12:00:00Z")
        val withUnknown = UsageAggregator.summarize(pack.calls, UsageAggregator.Range.ALL, zone, now)
        val rate = withUnknown.totals.hitRate()
        assertEquals((expected("fixture1")["hitRateNumerator"] as Number).toDouble() /
            (expected("fixture1")["hitRateDenominator"] as Number).toDouble(), rate!!, 1e-9)

        // No valid denominator at all -> null (UI shows —), not 0%.
        val only = listOf(row(100, 5, cacheRead = 0, provider = "kiro", cacheUnknown = false))
        val r = UsageAggregator.summarize(only, UsageAggregator.Range.ALL, zone, now)
        assertNull(r.totals.hitRate())
    }

    @Test
    fun `acceptance 5 - reasoning and cache_write_1h are not double counted`() {
        val rows = listOf(
            row(100, 10, cacheWrite = 30, cacheWrite1h = 12, reasoning = 999),
        )
        val t = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, Instant.parse("2026-03-31T00:00:00Z")).totals
        // allTokens = in+out+cr+cw only; reasoning/1h never add again.
        assertEquals(140L, t.allTokens)
        assertEquals(999L, t.reasoning)
        assertEquals(30L, t.cacheWrite)
        assertEquals(12L, t.cacheWrite1h)
    }

    @Test
    fun `acceptance 6 - handed-on tries are no request, retries and cancels told apart`() {
        val rows = listOf(
            row(10, 0, status = 500, err = "boom", handedOn = true),
            row(20, 5, tries = 2),
            row(30, 0, status = 499, errType = "agent_canceled", err = CallRow.AGENT_CANCELED),
            row(40, 1, status = 503, err = "overloaded"),
        )
        val t = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, Instant.parse("2026-03-31T00:00:00Z")).totals
        assertEquals(3L, t.calls) // handed-on excluded
        assertEquals(1L, t.errors)
        assertEquals(1L, t.retried)
        assertEquals(1L, t.canceled)
        // But the handed-on tokens are still in the sums:
        assertEquals(106L, t.allTokens)
    }

    @Test
    fun `acceptance 6b - a non-200 success is answered, not failed`() {
        val rows = listOf(row(10, 5, status = 201))
        val t = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, Instant.parse("2026-03-31T00:00:00Z")).totals
        assertEquals(1L, t.calls)
        assertEquals(0L, t.errors)
        assertTrue(rows.first().answered)
    }

    @Test
    fun `acceptance 6c - error text alone makes a failed call`() {
        val rows = listOf(row(10, 5, status = 200, err = "stream cut"))
        val t = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, Instant.parse("2026-03-31T00:00:00Z")).totals
        assertEquals(1L, t.errors)
    }

    @Test
    fun `acceptance 7 - beijing midnight boundary and long integers`() {
        // 2026-03-31T16:30:00Z is 2026-04-01 00:30 CST — belongs to April 1;
        // 2026-03-31T14:00:00Z is 2026-03-31 22:00 CST — belongs to March 31.
        val callA = row(7, 0).copy(t = Instant.parse("2026-03-31T16:30:00Z").toEpochMilli())
        val callB = row(3, 0).copy(t = Instant.parse("2026-03-31T14:00:00Z").toEpochMilli())
        val now = Instant.parse("2026-04-01T02:00:00Z") // 10:00 CST April 1
        val today = UsageAggregator.summarize(listOf(callA, callB), UsageAggregator.Range.TODAY, zone, now)
        assertEquals(1L, today.totals.calls) // only April-1's call; March 31's is before the window
        // Seen from March 31's evening, "today" holds the March-31 call. The
        // April-1 call is future-dated for that moment and upstream has no
        // upper bound on presets, so it is not what this check isolates.
        val yesterday = Instant.parse("2026-03-31T12:00:00Z") // 20:00 CST
        val before = UsageAggregator.summarize(listOf(callB), UsageAggregator.Range.TODAY, zone, yesterday)
        assertEquals(1L, before.totals.calls)

        // 30-day window edges: a call 29 days before today at midnight is in,
        // 30 days before is out.
        val dayMs = 24 * 3600 * 1000L
        val midnightToday = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val in30 = listOf(row(1).copy(t = midnightToday - 29 * dayMs))
        val out30 = listOf(row(1).copy(t = midnightToday - 30 * dayMs - 1))
        assertEquals(1L, UsageAggregator.summarize(in30, UsageAggregator.Range.D30, zone, now).totals.calls)
        assertEquals(0L, UsageAggregator.summarize(out30, UsageAggregator.Range.D30, zone, now).totals.calls)

        // 7 天 = 今天及之前 6 个自然日.
        val out7 = listOf(row(1).copy(t = midnightToday - 7 * dayMs))
        val in7 = listOf(row(1).copy(t = midnightToday - 6 * dayMs))
        assertEquals(1L, UsageAggregator.summarize(in7, UsageAggregator.Range.D7, zone, now).totals.calls)
        assertEquals(0L, UsageAggregator.summarize(out7, UsageAggregator.Range.D7, zone, now).totals.calls)

        // 21 亿+ tokens survive the Long path.
        val big = listOf(row(2_100_000_000L, 34_000_000L))
        val t = UsageAggregator.summarize(big, UsageAggregator.Range.ALL, zone, now).totals
        assertEquals(2_134_000_000L, t.allTokens)
    }

    @Test
    fun `provider breakdown sorts by all tokens then calls then id`() {
        val now = Instant.parse("2026-03-31T12:00:00Z")
        val result = UsageAggregator.summarize(pack.calls, UsageAggregator.Range.ALL, zone, now)
        val ids = result.providers.map { it.id }
        // 4200+5100... openai = 4200+450+0 = 4650; session-unknown = 2500; kiro = 5100
        // kiro(5100) > openai(4650) > session-unknown(2500) > anthropic(2000) > gemini(133)
        assertEquals(listOf("kiro", "openai", "session-unknown", "anthropic", "gemini"), ids)
    }

    @Test
    fun `rejected rows enter no figure`() {
        val rows = listOf(
            row(100, 10, status = 400, provider = ""),
            row(50, 5, rejected = true, provider = "x"),
            row(10, 1, provider = "x"),
        )
        val t = UsageAggregator.summarize(rows, UsageAggregator.Range.ALL, zone, Instant.parse("2026-03-31T00:00:00Z")).totals
        assertEquals(1L, t.calls)
        assertEquals(11L, t.allTokens)
    }

    @Test
    fun `a dst day has 23 or 25 hour buckets and late records stay in range`() {
        // 纽约 2026-11-01 是夏令时结束日：当地这一天有 25 小时。固定 24 个桶时，
        // 22:30 之后的记录会落到 series[24]，越界崩溃/丢数据。
        val ny = ZoneId.of("America/New_York")
        val endDay = java.time.LocalDate.of(2026, 11, 1)
        val late = row(100).copy(
            t = endDay.atTime(23, 30).atZone(ny).toInstant().toEpochMilli(),
        )
        val now = endDay.atTime(23, 50).atZone(ny).toInstant()
        val result = UsageAggregator.summarize(listOf(late), UsageAggregator.Range.TODAY, ny, now)
        assertEquals("25 小时的一天应有 25 个桶", 25, result.series.size)
        assertEquals(100L, result.series.last().totals.allTokens)

        // 春季进入夏令时那天只有 23 小时，最后一个桶是 22:00 起的那个小时。
        val springDay = java.time.LocalDate.of(2026, 3, 8)
        val night = row(70).copy(
            t = springDay.atTime(23, 10).atZone(ny).toInstant().toEpochMilli(),
        )
        val springNow = springDay.atTime(23, 50).atZone(ny).toInstant()
        val spring = UsageAggregator.summarize(listOf(night), UsageAggregator.Range.TODAY, ny, springNow)
        assertEquals("23 小时的一天应有 23 个桶", 23, spring.series.size)
        assertEquals(70L, spring.series.last().totals.allTokens)
    }

    @Test
    fun `a seven day window starts at local midnight across a dst change`() {
        // 跨 11-01（夏令时结束）的 7 天窗口必须仍然从当地 00:00 开始，
        // 而不是从「现在减去固定 6×24 小时」时刻开始。
        val ny = ZoneId.of("America/New_York")
        val now = java.time.LocalDate.of(2026, 11, 5).atTime(12, 0).atZone(ny).toInstant()
        val result = UsageAggregator.summarize(emptyList(), UsageAggregator.Range.D7, ny, now)
        val start = result.since!!.atZone(ny)
        assertEquals(0, start.hour)
        assertEquals(0, start.minute)
        assertEquals(java.time.LocalDate.of(2026, 10, 30), start.toLocalDate())
        assertEquals(7, result.series.size)
    }

    @Test
    fun `today buckets by hour and leaves future hours empty`() {
        // 08:00 CST call, now is 10:00 CST: only buckets 8 and 9..10 filled up to now.
        val now = Instant.parse("2026-03-31T02:00:00Z") // 10:00 CST
        val callAt8 = row(100).copy(t = Instant.parse("2026-03-31T00:00:00Z").toEpochMilli()) // 08:00 CST
        val callAt11 = row(50).copy(t = Instant.parse("2026-03-31T03:00:00Z").toEpochMilli()) // 11:00 CST, future
        val result = UsageAggregator.summarize(listOf(callAt8, callAt11), UsageAggregator.Range.TODAY, zone, now)
        assertEquals(24, result.series.size)
        assertEquals("00:00", result.series.first().label)
        assertEquals("23:00", result.series.last().label)
        assertEquals(100L, result.series[8].totals.allTokens)
        // A future-dated row (clock skew) is never drawn into a future hour
        // ("未来小时保留空白，不推测用量") — even though upstream's count
        // includes it in the window totals, the phone refuses to plot it.
        assertEquals(0L, result.series[11].totals.allTokens)
        assertEquals(150L, result.totals.allTokens)
    }

    @Test
    fun `all range goes weekly past 60 days starting monday`() {
        val now = Instant.parse("2026-06-01T00:00:00Z") // Monday
        val old = row(10).copy(t = Instant.parse("2026-02-10T00:00:00Z").toEpochMilli())
        val result = UsageAggregator.summarize(listOf(old), UsageAggregator.Range.ALL, zone, now)
        assertEquals("week", result.bucket)
        // First point starts on a Monday.
        val first = result.series.first().time.atZone(zone)
        assertEquals(java.time.DayOfWeek.MONDAY, first.dayOfWeek)
        assertTrue(result.series.size in 14..17)
    }

    @Test
    fun `provider filter keeps cards chart and list consistent`() {
        val now = Instant.parse("2026-03-31T12:00:00Z")
        val all = UsageAggregator.summarize(pack.calls, UsageAggregator.Range.ALL, zone, now)
        val filtered = UsageAggregator.summarize(pack.calls, UsageAggregator.Range.ALL, zone, now, providerFilter = setOf("openai"))
        assertEquals(4650L, filtered.totals.allTokens)
        assertEquals(all.totals.allTokens, filtered.totals.allTokens + 5100L + 2000L + 2500L + 133L)
        assertEquals(listOf("openai"), filtered.providers.map { it.id })
    }

    private fun row(
        inTok: Long,
        outTok: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        cacheWrite1h: Long = 0,
        reasoning: Long = 0,
        status: Int = 200,
        err: String = "",
        errType: String = "",
        tries: Int = 0,
        handedOn: Boolean = false,
        rejected: Boolean = false,
        provider: String = "p",
        cacheUnknown: Boolean = false,
    ): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d",
        t = Instant.parse("2026-03-31T01:00:00Z").toEpochMilli(),
        agent = "a", provider = provider, model = "m", requested = "", served = "",
        inTok = inTok, outTok = outTok, cacheRead = cacheRead, cacheWrite = cacheWrite,
        cacheWrite1h = cacheWrite1h, cacheUnknown = cacheUnknown, reasoning = reasoning, effort = "",
        ms = 0, ttftMs = 0, firstTextMs = 0, status = status, err = err, errType = errType,
        rid = "", session = "", source = "", tries = tries, handedOn = handedOn,
        rejected = rejected, costUsd = null, credits = null, upBytes = 0, downBytes = 0, raw = "",
    )
}
