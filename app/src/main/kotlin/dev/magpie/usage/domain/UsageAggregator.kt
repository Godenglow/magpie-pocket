package dev.magpie.usage.domain

import dev.magpie.usage.core.model.CallRow
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit

/**
 * The statistical engine: sums calls the way the desktop's Totals does.
 *
 * Reference: yetone/magpie @ 6acf5ee5, internal/usage/usage.go (Totals, add,
 * count, countCache, HitRate, Breakdown, timeline) and internal/usage/ledger.go
 * (Period.Since, Failed/Canceled/Answered).
 *
 * Rules carried over exactly:
 *  - rejected rows enter no figure ("本地拒绝的请求不计入汇总");
 *  - a handed-on try's tokens count, but it is no request of its own;
 *  - canceled (499 agent_canceled) is neither failed nor answered;
 *  - hit rate = cache_read / (in + cache_read + cache_write - cache_unknown),
 *    undefined when nothing is left; per-row means are never averaged;
 *  - cost only from an explicitly recorded price (the pack's cost field);
 *    a call with tokens and no price is Unpriced, and 0 ≠ absent.
 */
object UsageAggregator {

    /** The four windows the phone offers. */
    enum class Range { TODAY, D7, D30, ALL }

    enum class Metric { TOKENS, COST, CALLS }

    /**
     * 用量拆分维度，对应桌面端"拆分维度"。模型放第一位（默认口径），
     * 其后是供应商、模型·供应商、Agent、账号。key 是稳定分组键（配色也按它），
     * display 是展示名。
     */
    enum class Dimension(val title: String) {
        MODEL("模型"),
        PROVIDER("供应商"),
        MODEL_PROVIDER("模型·供应商"),
        AGENT("Agent"),
        ACCOUNT("账号");

        fun key(r: CallRow): String = when (this) {
            PROVIDER -> r.provider
            MODEL -> r.model
            MODEL_PROVIDER -> r.provider + "/" + r.model
            AGENT -> r.agent
            ACCOUNT -> CallRow.accountOf(r.host, r.providerAccount)
        }

        fun display(key: String): String = when (this) {
            PROVIDER -> dev.magpie.usage.domain.Providers.displayName(key)
            MODEL -> key.ifEmpty { "未知模型" }
            MODEL_PROVIDER ->
                key.substringAfter('/').ifEmpty { "未知模型" } + " · " +
                    dev.magpie.usage.domain.Providers.displayName(key.substringBefore('/'))
            AGENT -> key.ifEmpty { "未知来源" }
            ACCOUNT -> key.ifEmpty { "未知账号" }
        }

        /** 配色按这个子键取，模型维度跟随模型名（与桌面一致）。 */
        fun colorKey(key: String): String = when (this) {
            PROVIDER -> key
            MODEL, MODEL_PROVIDER -> key.substringAfter('/')
            AGENT, ACCOUNT -> key
        }
    }

    data class Totals(
        var calls: Long = 0,
        var errors: Long = 0,
        var retried: Long = 0,
        var canceled: Long = 0,
        var input: Long = 0,
        var output: Long = 0,
        var cacheRead: Long = 0,
        var cacheWrite: Long = 0,
        var cacheWrite1h: Long = 0,
        var cacheUnknown: Long = 0,
        var cacheUnknownCalls: Long = 0,
        var reasoning: Long = 0,
        var costUsd: Double = 0.0,
        var unpriced: Long = 0,
        var credits: Double = 0.0,
        var credited: Long = 0,
    ) {
        /** Token（含缓存）: everything that moved through the model. */
        val allTokens: Long get() = input + output + cacheRead + cacheWrite

        /** Upstream Totals.Tokens: in and out, cache aside. */
        val plainTokens: Long get() = input + output

        fun add(r: CallRow, cost: Double?) {
            count(r)
            input += r.inTok
            output += r.outTok
            cacheRead += r.cacheRead
            cacheWrite += r.cacheWrite
            cacheWrite1h += r.cacheWrite1h
            if (r.cacheNotCounted) {
                cacheUnknown += r.cacheUnknownIn
                cacheUnknownCalls++
            }
            reasoning += r.reasoning
            if (r.credits != null) {
                credits += r.credits
                credited++
            }
            if (r.inTok + r.outTok == 0L) return
            // cost == null means unknown (Unpriced); a known 0 is a price,
            // deliberately. The caller resolves: explicit record cost first,
            // then the catalog (PriceTable), like CostAt over Pricer.
            if (cost == null) {
                unpriced++
            } else {
                costUsd += cost
            }
        }

        /** Upstream Totals.count: the request this row ends. */
        private fun count(r: CallRow) {
            if (r.handedOn) return
            calls++
            when {
                r.canceled -> canceled++
                r.failed -> errors++
                r.tries > 1 -> retried++
            }
        }

        /** Cache hit rate, or null when there is no valid denominator. */
        fun hitRate(): Double? {
            val denominator = input + cacheRead + cacheWrite - cacheUnknown
            if (denominator <= 0) return null
            return cacheRead.toDouble() / denominator.toDouble()
        }
    }

    /** One provider's share, sorted as upstream's Breakdown sorts. */
    data class ProviderShare(
        val id: String,
        val totals: Totals,
    )

    /** 概览卡片：一个账号（Record.Account 口径）的用量。 */
    data class AccountShare(
        val account: String,
        val totals: Totals,
    )

    /** 概览卡片：一个供应商的总量与其下所有账号的拆分。 */
    data class ProviderUsageShare(
        val provider: String,
        val totals: Totals,
        val accounts: List<AccountShare>,
    )

    data class SeriesPoint(
        val time: Instant,
        val label: String,
        val totals: Totals,
        /** Per-provider values for the stacked chart, largest last. */
        val providers: MutableMap<String, Totals> = LinkedHashMap(),
    )

    data class Result(
        val totals: Totals,
        val providers: List<ProviderShare>,
        val series: List<SeriesPoint>,
        val bucket: String, // "hour" | "day" | "week"
        val since: Instant?,
        /** Earliest and latest record times actually held, for the All view. */
        val firstRecordAt: Instant?,
        val lastRecordAt: Instant?,
    )

    /**
     * Sums [rows] over [range] in [zone]. [providerFilter] null = all;
     * otherwise the allowed provider ids. [dimension] chooses the breakdown
     * grouping (chart stack and list share it). [priceOf] prices a record
     * from the catalog (null = only explicit record costs count — the
     * desktop's CostAt over Pricer). [now] is injectable for tests.
     */
    fun summarize(
        rows: List<CallRow>,
        range: Range,
        zone: ZoneId,
        now: Instant = Instant.now(),
        providerFilter: Set<String>? = null,
        dimension: Dimension = Dimension.PROVIDER,
        priceOf: ((CallRow) -> Double?)? = null,
    ): Result {
        val since: Instant? = sinceOf(range, zone, now)

        // Coverage: what the phone actually holds, regardless of the window.
        var firstRecord: Instant? = null
        var lastRecord: Instant? = null
        for (r in rows) {
            val t = Instant.ofEpochMilli(r.t)
            if (firstRecord == null || t.isBefore(firstRecord)) firstRecord = t
            if (lastRecord == null || t.isAfter(lastRecord)) lastRecord = t
        }

        val nowZoned = now.atZone(zone)
        val today = nowZoned.toLocalDate()
        val totals = Totals()
        val byProvider = LinkedHashMap<String, Totals>()
        var bucket = "day"
        var series: List<SeriesPoint> = emptyList()
        var seriesStart: ZonedDateTime? = null

        when (range) {
            Range.TODAY -> {
                bucket = "hour"
                // A DST day is 23 or 25 hours long: build one bucket per
                // actual hour, or the evening's records fall past the end of
                // a fixed 24 (series[24] out of bounds).
                seriesStart = today.atStartOfDay(zone)
                series = hourPoints(seriesStart, hoursInDay(today, zone))
            }
            Range.D7 -> {
                seriesStart = today.minusDays(6).atStartOfDay(zone)
                series = dayPoints(seriesStart, 7)
            }
            Range.D30 -> {
                seriesStart = today.minusDays(29).atStartOfDay(zone)
                series = dayPoints(seriesStart, 30)
            }
            Range.ALL -> {
                val startDate = firstRecord?.atZone(zone)?.toLocalDate()
                if (startDate != null) {
                    val start = startDate.atStartOfDay(zone)
                    val days = ChronoUnit.DAYS.between(startDate, today).toInt() + 1
                    if (days > 60) {
                        // Past 60 days a week each, starting the first Monday.
                        bucket = "week"
                        val weekStartDate = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        val weekStart = weekStartDate.atStartOfDay(zone)
                        seriesStart = weekStart
                        val weeks = (ChronoUnit.DAYS.between(weekStartDate, today) / 7).toInt() + 1
                        series = weekPoints(weekStart, weeks)
                    } else {
                        series = dayPoints(start, days)
                        seriesStart = start
                    }
                }
            }
        }

        val seriesIndex = HashMap<Instant, Int>(series.size)
        series.forEachIndexed { i, p -> seriesIndex[p.time] = i }

        for (r in rows) {
            if (r.isRejected) continue
            if (providerFilter != null && r.provider !in providerFilter) continue
            val t = Instant.ofEpochMilli(r.t)
            if (since != null && t.isBefore(since)) continue
            val dimKey = dimension.key(r)
            // Explicit record cost first (OwnCost), else the catalog's price
            // at the call's own time; null keeps the row Unpriced.
            val cost = r.costUsd ?: priceOf?.invoke(r)
            totals.add(r, cost)
            byProvider.getOrPut(dimKey) { Totals() }.add(r, cost)
            if (seriesStart != null && series.isNotEmpty()) {
                val i = bucketIndex(bucket, seriesStart, t.atZone(zone), nowZoned)
                // A clock-skewed record can land past the series' end (and a
                // zone's own quirks with it): keep it in the totals, but never
                // index outside the chart.
                if (i != null && i in series.indices) {
                    series[i].totals.add(r, cost)
                    series[i].providers.getOrPut(dimKey) { Totals() }.add(r, cost)
                }
            }
        }

        val providers = byProvider.map { (id, t) -> ProviderShare(id, t) }.sortedWith(
            compareByDescending<ProviderShare> { it.totals.allTokens }
                .thenByDescending { it.totals.calls }
                .thenBy { it.id }
        )

        return Result(totals, providers, series, bucket, since, firstRecord, lastRecord)
    }

    /**
     * 概览的"供应商用量"：按供应商分组，每组给出总量（默认展示）与其下
     * 各账号的拆分（点击展开）。账号口径同 [Dimension.ACCOUNT]。
     * 拒绝行不入汇总、providerFilter 生效，与 [summarize] 一致。
     */
    fun byProviderAccount(
        rows: List<CallRow>,
        range: Range,
        zone: ZoneId,
        now: Instant = Instant.now(),
        providerFilter: Set<String>? = null,
        priceOf: ((CallRow) -> Double?)? = null,
    ): List<ProviderUsageShare> {
        val since: Instant? = sinceOf(range, zone, now)
        val providerTotals = LinkedHashMap<String, Totals>()
        val accountTotals = HashMap<String, MutableMap<String, Totals>>()
        for (r in rows) {
            if (r.isRejected) continue
            if (providerFilter != null && r.provider !in providerFilter) continue
            val t = Instant.ofEpochMilli(r.t)
            if (since != null && t.isBefore(since)) continue
            val cost = r.costUsd ?: priceOf?.invoke(r)
            val account = Dimension.ACCOUNT.key(r)
            providerTotals.getOrPut(r.provider) { Totals() }.add(r, cost)
            accountTotals.getOrPut(r.provider) { LinkedHashMap() }
                .getOrPut(account) { Totals() }.add(r, cost)
        }
        return providerTotals.map { (provider, totals) ->
            val accounts = accountTotals[provider].orEmpty().map { (account, at) ->
                AccountShare(account, at)
            }.sortedWith(
                compareByDescending<AccountShare> { it.totals.allTokens }
                    .thenByDescending { it.totals.calls }
                    .thenBy { it.account },
            )
            ProviderUsageShare(provider, totals, accounts)
        }.sortedWith(
            compareByDescending<ProviderUsageShare> { it.totals.allTokens }
                .thenByDescending { it.totals.calls }
                .thenBy { it.provider },
        )
    }

    private fun hourPoints(start: ZonedDateTime, n: Int): List<SeriesPoint> =
        (0 until n).map { h ->
            val t = start.plusHours(h.toLong())
            SeriesPoint(t.toInstant(), String.format("%02d:00", t.hour), Totals())
        }

    private fun dayPoints(start: ZonedDateTime, n: Int): List<SeriesPoint> =
        (0 until n).map { d ->
            val t = start.plusDays(d.toLong())
            SeriesPoint(t.toInstant(), "${t.monthValue}/${t.dayOfMonth}", Totals())
        }

    private fun weekPoints(start: ZonedDateTime, n: Int): List<SeriesPoint> =
        (0 until n).map { w ->
            val t = start.plusWeeks(w.toLong())
            SeriesPoint(t.toInstant(), "${t.monthValue}/${t.dayOfMonth}", Totals())
        }

    /** How long this local day is: 24 hours, 23 or 25 across a DST change. */
    private fun hoursInDay(date: java.time.LocalDate, zone: ZoneId): Int =
        Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone))
            .toHours().toInt()

    /**
     * A day's or week's window boundary: a local midnight, not a fixed 24
     * hours after the last one (a DST change makes those differ).
     */
    private fun startOfDay(date: java.time.LocalDate, zone: ZoneId): ZonedDateTime =
        date.atStartOfDay(zone)

    /**
     * The window [range] starts at: local midnights counted on the calendar,
     * so "7 天" still begins at midnight after a DST change.
     */
    private fun sinceOf(range: Range, zone: ZoneId, now: Instant): Instant? {
        val today = now.atZone(zone).toLocalDate()
        return when (range) {
            Range.TODAY -> startOfDay(today, zone).toInstant()
            Range.D7 -> startOfDay(today.minusDays(6), zone).toInstant()
            Range.D30 -> startOfDay(today.minusDays(29), zone).toInstant()
            Range.ALL -> null
        }
    }

    /** Index of [t] in the series; null keeps the point out (before the
     *  series or in a bucket that has not begun). */
    private fun bucketIndex(bucket: String, start: ZonedDateTime, t: ZonedDateTime, now: ZonedDateTime): Int? {
        return when (bucket) {
            "hour" -> {
                if (t.isAfter(now)) return null
                // Elapsed hours, as the buckets were built: a DST day's 25th
                // hour is index 24, and the series has 25 entries for it.
                val h = Duration.between(start, t).toHours()
                h.toInt().takeIf { it >= 0 }
            }
            "day" -> {
                // Calendar days, not 24-hour steps: two local midnights are
                // 23 or 25 hours apart across a DST change.
                val d = ChronoUnit.DAYS.between(start.toLocalDate(), t.toLocalDate())
                d.toInt().takeIf { it >= 0 }
            }
            "week" -> {
                val weekStart = t.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val w = ChronoUnit.DAYS.between(start.toLocalDate(), weekStart) / 7
                w.toInt().takeIf { it >= 0 }
            }
            else -> null
        }
    }
}
