package dev.magpie.usage.domain

import dev.magpie.usage.core.model.CallRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 用量热力图：最近 53 周的每日用量，GitHub 式网格 —— 周一为第一行、
 * 每列一周，与桌面端一致。数据来自手机本机已导入的快照（云端只有
 * 最近 90 天，更早的格子依赖本机归档，没有就是空）。
 */
object UsageHeatmap {

    enum class Metric { TOKENS, CALLS, COST }

    data class DayCell(
        val date: LocalDate,
        val totals: UsageAggregator.Totals,
    )

    data class WeekColumn(
        val monday: LocalDate,
        val days: List<DayCell>,
    )

    data class Heatmap(
        val weeks: List<WeekColumn>,
        /** Column index -> "11月", set when that column starts a new month. */
        val monthLabels: Map<Int, String>,
        val firstDataDate: LocalDate?,
        val lastDataDate: LocalDate?,
    )

    const val DEFAULT_WEEKS = 53

    fun metricValue(cell: DayCell, metric: Metric): Double = when (metric) {
        Metric.TOKENS -> cell.totals.allTokens.toDouble()
        Metric.CALLS -> cell.totals.calls.toDouble()
        Metric.COST -> cell.totals.costUsd
    }

    /** 0 (无/零) … 4 (最忙)，开方缓放：一个极端大日不会洗白其余日子。 */
    fun level(value: Double, max: Double): Int {
        if (max <= 0.0 || value <= 0.0) return 0
        return 1 + floor(sqrt(value / max) * 3.999).toInt().coerceIn(0, 3)
    }

    fun build(
        rows: List<CallRow>,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone),
        weeks: Int = DEFAULT_WEEKS,
        providerFilter: Set<String>? = null,
        priceOf: ((CallRow) -> Double?)? = null,
    ): Heatmap {
        // 按自然日聚合（读者的时区），拒绝行不入任何图。
        val byDay = HashMap<LocalDate, UsageAggregator.Totals>()
        var first: LocalDate? = null
        var last: LocalDate? = null
        for (r in rows) {
            if (r.isRejected) continue
            if (providerFilter != null && r.provider !in providerFilter) continue
            val day = java.time.Instant.ofEpochMilli(r.t).atZone(zone).toLocalDate()
            val cost = r.costUsd ?: priceOf?.invoke(r)
            byDay.getOrPut(day) { UsageAggregator.Totals() }.add(r, cost)
            if (first == null || day.isBefore(first)) first = day
            if (last == null || day.isAfter(last)) last = day
        }

        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = thisMonday.minusWeeks((weeks - 1).toLong())
        val columns = ArrayList<WeekColumn>(weeks)
        var previousMonth: java.time.Month? = null
        val monthLabels = LinkedHashMap<Int, String>()
        for (i in 0 until weeks) {
            val monday = start.plusWeeks(i.toLong())
            if (i > 0 && monday.month != previousMonth) {
                monthLabels[i] = "${monday.monthValue}月"
            }
            previousMonth = monday.month
            columns.add(
                WeekColumn(
                    monday = monday,
                    days = (0L..6L).map { d ->
                        val date = monday.plusDays(d)
                        DayCell(date, byDay[date] ?: UsageAggregator.Totals())
                    },
                )
            )
        }

        return Heatmap(columns, monthLabels, first, last)
    }

    /** 指标自己的上限（用于配色分级）。 */
    fun maxOf(heatmap: Heatmap, metric: Metric): Double =
        heatmap.weeks.maxOf { col -> col.days.maxOf { metricValue(it, metric) } }
}
