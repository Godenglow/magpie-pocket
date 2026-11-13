package dev.magpie.usage.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Chinese display formatting: 万/亿 for the big figures, precise values one
 * long-press away. The unit never feeds back into the statistics.
 */
object Formatters {

    /** 数字单位："cn"=万/亿，"en"=K/M/B（默认）。由设置页写入。 */
    @Volatile var numberUnit: String = "en"

    /** 货币："usd"=$（默认），"cny"=¥（按 [exchangeRate] 折算，非实时汇率）。 */
    @Volatile var currency: String = "usd"

    /** 人民币折算汇率（用户在设置里自定；不是牌价、不自动更新）。 */
    @Volatile var exchangeRate: Double = 7.0

    /** 3.10 亿 / 1177.7 万 / 2081 / 0；英文模式 1.8B / 180.3M / 2.1K。 */
    fun tokens(value: Long): String =
        if (numberUnit == "en") shortEn(value.toDouble()) else cnTokens(value)

    private fun cnTokens(value: Long): String {
        val v = abs(value)
        return when {
            v >= 100_000_000 -> {
                val n = value / 100_000_000.0
                trim(n, 2) + " 亿"
            }
            v >= 10_000 -> {
                val n = value / 10_000.0
                trim(n, 1) + " 万"
            }
            else -> value.toString()
        }
    }

    private fun shortEn(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1_000_000_000 -> trim(v / 1_000_000_000.0, 2) + "B"
            a >= 1_000_000 -> trim(v / 1_000_000.0, 1) + "M"
            a >= 1_000 -> trim(v / 1_000.0, 1) + "K"
            else -> v.toLong().toString()
        }
    }

    /** Counts (requests): no unit below 1 万, then 万. */
    fun count(value: Long): String =
        if (numberUnit == "en") shortEn(value.toDouble()) else cnCount(value)

    private fun cnCount(value: Long): String {
        val v = abs(value)
        return when {
            v >= 100_000_000 -> trim(value / 100_000_000.0, 2) + " 亿"
            v >= 10_000 -> trim(value / 10_000.0, 1) + " 万"
            else -> value.toString()
        }
    }

    /** Bytes: "6.0 GB" / "900 MB", keeping the decimal the desktop shows. */
    fun bytes(value: Long): String = when {
        value >= 1L shl 30 -> "%.1f".format(value / (1L shl 30).toDouble()) + " GB"
        value >= 1L shl 20 -> "%.0f".format(value / (1L shl 20).toDouble()) + " MB"
        value >= 1L shl 10 -> "%.0f".format(value / (1L shl 10).toDouble()) + " KB"
        else -> "$value B"
    }

    /**
     * Reference cost, USD or CNY. Upstream rounds to whole dollars at large amounts,
     * two decimals below 100. The "+" marks a partially priced window.
     * 不再显示 `≈`，直接显示金额。
     */
    fun cost(usd: Double, hasUnpriced: Boolean): String {
        val amount = if (currency == "cny") usd * exchangeRate else usd
        val formatted = when {
            amount >= 10_000 -> "%.0f".format(amount)
            amount >= 100 -> "%.1f".format(amount).removeSuffix(".0")
            amount >= 1 -> "%.2f".format(amount)
            amount == 0.0 -> "0"
            else -> "%.3f".format(amount).trimEnd('0').trimEnd('.')
        }
        val symbol = if (currency == "cny") "¥" else "$"
        return "$symbol$formatted${if (hasUnpriced) "+" else ""}"
    }

    fun percent(fraction: Double): String {
        val p = fraction * 100
        val s = when {
            p >= 100 -> if (p >= 99.995) "100" else "%.2f".format(p)
            p >= 1 -> "%.0f".format(p).let { whole ->
                if (whole == "0" || whole == "100") "%.2f".format(p) else whole
            }
            else -> "%.2f".format(p)
        }
        return "$s%"
    }

    fun time(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    fun clock(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    fun dateTime(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    fun date(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    private fun trim(n: Double, maxDecimals: Int): String {
        val s = "%.${maxDecimals}f".format(n)
        // Strip trailing zeros only past the decimal point: "3.10"→"3.1",
        // while "900" keeps every digit.
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }
}
