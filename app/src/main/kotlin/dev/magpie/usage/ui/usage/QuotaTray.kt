package dev.magpie.usage.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magpie.usage.domain.Providers
import dev.magpie.usage.domain.QuotaOverviewBuilder
import dev.magpie.usage.ui.quotaRingColumns
import dev.magpie.usage.ui.rememberWidthClass
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Page
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 额度（托盘样式）：照抄单击 magpie 托盘弹出的额度面板。
 *
 *  - 供应商行：色块图标 + 大写名 + 账号数徽标 + 「剩余/已用」切换；
 *  - 「全部 N 个账号」合并条：每个窗口一个百分比圆环 + 名称 + 读数，
 *    右上角箭头展开/收起各账号卡（**默认折叠**）；
 *  - 账号卡：账号名 + 每窗口一个圆环 + 重置时间 + 走势（实线历史、
 *    虚线按最近速度外推到重置）；
 *  - 在「整理」里取消勾选的账号不显示，但仍计入合并条。
 *
 * 圆环统一表达百分比（托盘用环、网页版用条），这里全部用环。
 */

private val Amber = Color(0xFFE2B04A)
private val RingTrack: Color @Composable get() = dev.magpie.usage.ui.theme.SoftSurface

/** 账号展示键：provider|user，与 OrganizeState.hiddenAccounts 一致。 */
fun accountKey(provider: String, user: String): String = "$provider|$user"

@Composable
fun QuotaTraySection(
    groups: List<QuotaOverviewBuilder.GroupView>,
    zone: ZoneId,
    usedMode: Boolean,
    onToggleMode: () -> Unit,
    hiddenAccounts: Set<String> = emptySet(),
) {
    // 展开状态默认全部折叠（用户要求），只在本页内存里记住。
    val expanded = remember { mutableStateOf(setOf<String>()) }
    groups.forEach { group ->
        val shownAccounts = group.accounts.filter { accountKey(group.provider, it.user) !in hiddenAccounts }
        QuotaTrayProvider(
            group = group,
            shownAccounts = shownAccounts,
            zone = zone,
            usedMode = usedMode,
            expanded = group.provider in expanded.value,
            onToggleExpanded = {
                expanded.value = if (group.provider in expanded.value) {
                    expanded.value - group.provider
                } else {
                    expanded.value + group.provider
                }
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QuotaTrayProvider(
    group: QuotaOverviewBuilder.GroupView,
    shownAccounts: List<QuotaOverviewBuilder.AccountView>,
    zone: ZoneId,
    usedMode: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val color = Color(Providers.palette[Providers.colorIndex(group.provider)])
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Providers.displayName(group.provider).take(1).uppercase(),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = dev.magpie.usage.ui.theme.OnAccent,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                Providers.displayName(group.provider).uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 账号数徽标就是展开开关：一个账号的供应商也先给总览网格，
            // 点它才看单账号的细节（重置时间、走势）。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (expanded) Purple.copy(alpha = 0.22f) else CardBorder.copy(alpha = 0.8f))
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    "${group.accounts.size}",
                    fontSize = 12.sp,
                    color = if (expanded) Purple else TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (group.merged.isNotEmpty()) {
            // 总览网格（所有账号的窗口平均）：账号细节在展开时列在下面。
            MergedAccountsCard(
                merged = group.merged,
                usedMode = usedMode,
                zone = zone,
            )
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                if (shownAccounts.isEmpty()) {
                    Text("账号都被隐藏了，可在「整理」里恢复", fontSize = 12.sp, color = TextSecondary)
                } else {
                    shownAccounts.forEach { account ->
                        AccountQuotaCard(account, zone, usedMode)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            // 没有任何可汇总的窗口：直接列出账号卡，不留空标题行。
            if (shownAccounts.isEmpty()) {
                Text("账号都被隐藏了，可在「整理」里恢复", fontSize = 12.sp, color = TextSecondary)
            } else {
                shownAccounts.forEach { account ->
                    AccountQuotaCard(account, zone, usedMode)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeChip(usedMode: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (usedMode) Purple.copy(alpha = 0.22f) else CardBorder.copy(alpha = 0.8f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(if (usedMode) "已用" else "剩余", fontSize = 12.sp, color = TextPrimary)
    }
}

/** 页面级的「剩余 / 已用」切换（顶部一行）。 */
@Composable
fun ModeToggle(usedMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(CardBorder.copy(alpha = 0.55f)),
    ) {
        listOf(false to "剩余", true to "已用").forEach { (value, label) ->
            val on = value == usedMode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) Purple.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable { if (!on) onToggle() }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (on) dev.magpie.usage.ui.theme.OnAccent else TextSecondary,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 供应商总览的合并条：与单账号卡同样的圆环网格（环 + 环下窗口名），
 * 手机每行 2 个、平板一行 4 个。窗口顺序按桌面的读法：
 * Total credits → Credits → Extra credits → 其余（deepseek tokens 估算）。
 */
@Composable
private fun MergedAccountsCard(
    merged: List<QuotaOverviewBuilder.MergedWindow>,
    usedMode: Boolean,
    zone: ZoneId,
) {
    val columns = rememberWidthClass().quotaRingColumns()
    val ordered = remember(merged) { merged.sortedWith(compareBy { windowRank(it.name) }) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        ordered.chunked(columns).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { w ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        MergedWindowDial(w, usedMode, zone)
                    }
                }
                // 最后一行不满时补空位，让各列保持等宽对齐。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 一个窗口的圆环 + 环下名称（与单账号卡一致）；过期读数标灰并注明时间。 */
@Composable
private fun MergedWindowDial(w: QuotaOverviewBuilder.MergedWindow, usedMode: Boolean, zone: ZoneId) {
    val left = w.leftPercent
    val shown = if (usedMode) 100.0 - left else left
    val critical = !w.stale && (if (usedMode) shown >= 90.0 else left <= 10.0)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PercentRing(shown, critical, 54.dp, 4.dp, labelSize = 14.sp, dimmed = w.stale)
        Spacer(Modifier.height(6.dp))
        Text(
            w.name,
            fontSize = 11.sp,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            "读数 ${readingClock(w.at, zone)}" +
                (if (w.stale) " · ${if (w.staleAccounts == w.accounts) "已过期" else "含过期读数"}" else ""),
            fontSize = 10.sp,
            color = if (w.stale) Amber else TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 读数时间：今天只给时分，更早给月日。 */
private fun readingClock(at: Long, zone: ZoneId): String {
    if (at <= 0) return "—"
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    return if (t.toLocalDate() == today) {
        "%02d:%02d".format(t.hour, t.minute)
    } else {
        "${t.monthValue}月${t.dayOfMonth}日 %02d:%02d".format(t.hour, t.minute)
    }
}

/**
 * 窗口在总览里的次序，按桌面概览的读法：合计在前，其次基础额度、
 * 额外额度，其余（如 deepseek tokens 估算）最后。未知名称排在中段。
 */
private fun windowRank(name: String): Int {
    val n = name.lowercase()
    return when {
        n.contains("total") -> 0
        n.contains("extra") -> 2
        n.contains("credit") -> 1
        n.contains("token") -> 3
        else -> 2
    }
}

@Composable
private fun AccountQuotaCard(
    account: QuotaOverviewBuilder.AccountView,
    zone: ZoneId,
    usedMode: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                account.user.ifEmpty { "默认账号" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        // 每窗口一个环，与供应商总览同一套网格：手机 2 列、平板 4 列。
        val columns = rememberWidthClass().quotaRingColumns()
        val windows = remember(account.windows) {
            account.windows.sortedWith(compareBy { windowRank(it.name) })
        }
        windows.chunked(columns).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { w ->
                    val shown = if (usedMode) 100.0 - w.leftPercent else w.leftPercent
                    val critical = !w.stale && (if (usedMode) shown >= 90.0 else w.leftPercent <= 10.0)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PercentRing(shown, critical, 54.dp, 4.dp, labelSize = 14.sp, dimmed = w.stale)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                w.name,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "读数 ${readingClock(w.at, zone)}${if (w.stale) " · 已过期" else ""}",
                                fontSize = 10.sp,
                                color = if (w.stale) Amber else TextSecondary,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        resetLineOf(account, zone)?.let { line ->
            Spacer(Modifier.height(10.dp))
            Text(line, fontSize = 12.sp, color = TextSecondary)
        }
        val withTrend = account.windows.count { it.trend.size >= 2 }
        if (withTrend > 0) {
            Spacer(Modifier.height(8.dp))
            TrendSparkline(account.windows)
        }
    }
}

/** 百分比圆环：环上缺口即用量；耗尽/见底转琥珀色（同桌面 0% 环）。
 *  [dimmed] 是过期读数：弧线与数字都淡下去，因为那不是现在的余额。 */
@Composable
private fun PercentRing(
    percent: Double,
    critical: Boolean,
    size: androidx.compose.ui.unit.Dp,
    stroke: androidx.compose.ui.unit.Dp,
    labelSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    dimmed: Boolean = false,
) {
    val alpha = if (dimmed) 0.45f else 1f
    val ringColor = (if (critical) Amber else Purple).copy(alpha = alpha)
    val ringTrack = RingTrack
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2 + 0.5f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(
                color = ringTrack,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = w),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = (percent.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat(),
                useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = w),
            )
        }
        Text(
            "${percent.toInt().coerceIn(0, 100)}%",
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary.copy(alpha = alpha),
        )
    }
}

private fun resetLineOf(account: QuotaOverviewBuilder.AccountView, zone: ZoneId): String? {
    val fmt = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(zone)
    val withReset = account.windows.mapNotNull { w -> w.resetsAt?.let { w to it } }
    if (withReset.isEmpty()) return null
    val head = "重置于 ${fmt.format(Instant.ofEpochMilli(withReset.first().second))}"
    val second = withReset.drop(1).firstOrNull { it.second != withReset.first().second }
    return if (second == null) head
    else "$head · ${second.first.name} ${fmt.format(Instant.ofEpochMilli(second.second))}"
}

/** 剩余走势：实线历史 + 虚线按最近速度外推到重置。 */
@Composable
private fun TrendSparkline(windows: List<QuotaOverviewBuilder.WindowView>) {
    val lines = windows.filter { it.trend.size >= 2 }
    if (lines.isEmpty()) return
    val t0 = lines.minOf { it.trend.first().first }
    val t1 = maxOf(
        lines.maxOf { it.trend.last().first },
        lines.mapNotNull { it.resetsAt }.maxOrNull() ?: 0L,
    ).coerceAtLeast(t0 + 1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Page),
    ) {
        lines.forEachIndexed { i, w ->
            val color = Color(Providers.palette[(Providers.colorIndex(w.name) + i) % Providers.palette.size])
            fun x(at: Long) = size.width * (at - t0) / (t1 - t0)
            fun y(left: Double) = size.height * (1f - (left / 100.0).toFloat())
            val path = Path()
            w.trend.forEachIndexed { j, (at, left) ->
                val p = Offset(x(at), y(left))
                if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.8.dp.toPx()))
            val last = w.trend.last()
            val prev = w.trend[w.trend.size - 2]
            val rate = (last.second - prev.second) / (last.first - prev.first).coerceAtLeast(1).toDouble()
            val endAt = (w.resetsAt ?: t1).coerceAtMost(t1)
            if (endAt > last.first) {
                val endLeft = (last.second + rate * (endAt - last.first)).coerceIn(0.0, 100.0)
                drawLine(
                    color = color.copy(alpha = 0.45f),
                    start = Offset(x(last.first), y(last.second)),
                    end = Offset(x(endAt), y(endLeft)),
                    strokeWidth = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
    }
}

/** 一位小数的百分比，整数不带小数点（61.5% / 38%）。 */
private fun pct1(v: Double): String {
    val r = Math.round(v * 10) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
