package dev.magpie.usage.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.domain.Formatters
import dev.magpie.usage.domain.Providers
import dev.magpie.usage.domain.UsageAggregator
import dev.magpie.usage.ui.rememberWidthClass
import dev.magpie.usage.ui.statColumns
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Coral
import dev.magpie.usage.ui.theme.Cyan
import dev.magpie.usage.ui.theme.Mint
import dev.magpie.usage.ui.theme.Page
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary

@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Page)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        DashboardHeader("用量", state.syncState == UsageViewModel.SyncState.CHECKING,
            onRefresh = { viewModel.refresh() }, onSettings = onOpenSettings,
            onTheme = viewModel::setTheme)
        Spacer(Modifier.height(4.dp))
        SyncStatusLine(state)
        Spacer(Modifier.height(12.dp))
        RangeSelector(state.range) { viewModel.setRange(it) }
        Spacer(Modifier.height(8.dp))
        FilterRow(state.providerIds, state.selectedProviders) { viewModel.setSelectedProviders(it) }
        Spacer(Modifier.height(8.dp))

        val result = state.result
        when {
            state.loading -> Centered { Text("加载中…", color = TextSecondary) }
            state.settings?.configured != true -> NotConfigured(onOpenSettings)
            result == null -> Centered { Text("暂无数据", color = TextSecondary) }
            else -> {
                if (result.totals.calls == 0L && result.totals.input == 0L && result.totals.output == 0L &&
                    result.totals.cacheRead == 0L && result.totals.cacheWrite == 0L
                ) {
                    EmptyRange(state)
                } else {
                    SummaryCards(result, state.priceSource)
                    Spacer(Modifier.height(12.dp))
                    ChartCard(result, state.dimension, state.zone)
                    Spacer(Modifier.height(12.dp))
                    BreakdownCard(result, state.dimension) { viewModel.setDimension(it) }
                    Spacer(Modifier.height(12.dp))
                    HeatmapCard(state.heatmap, state.zone)
                    Spacer(Modifier.height(8.dp))
                    CoverageNote(state, result)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
internal fun SyncStatusLine(state: UsageViewModel.UiState) {
    val text = when (state.syncState) {
        UsageViewModel.SyncState.CHECKING -> "正在刷新…"
        UsageViewModel.SyncState.ERROR -> "刷新失败：${state.syncMessage ?: "未知原因"}"
        else -> {
            val last = state.lastCheckedAt
            if (last == null) "尚未刷新"
            else {
                val zone = state.zone
                val checked = Formatters.clock(last, zone)
                val record = state.latestRecordAt?.let { Formatters.clock(it, zone) }
                if (record != null) "最近刷新 $checked · 云端最新数据 $record"
                else "最近刷新 $checked · 暂无记录"
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dotColor = when (state.syncState) {
            UsageViewModel.SyncState.ERROR -> Coral
            UsageViewModel.SyncState.CHECKING -> Purple
            else -> if (state.lastCheckedAt != null) Mint else TextSecondary
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 12.sp,
            color = if (state.syncState == UsageViewModel.SyncState.ERROR) Coral else TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分段控件。[scrollable] = 选项多、标签长时横向滚动（每段按内容宽度，
 * 不换行、不挤压），否则等分填满。用量拆分的五个维度用可滚动版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    scrollable: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    if (scrollable) {
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label ->
                FilterChip(selected = i == selectedIndex, onClick = { onSelected(i) },
                    label = { Text(label, fontSize = fontSize) })
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                SegmentedButton(selected = i == selectedIndex, onClick = { onSelected(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {}, label = { Text(label, fontSize = fontSize, maxLines = 1) })
            }
        }
    }
}

@Composable
private fun RangeSelector(range: UsageAggregator.Range, onRange: (UsageAggregator.Range) -> Unit) {
    val options = listOf("今天", "7 天", "30 天", "全部")
    val values = listOf(
        UsageAggregator.Range.TODAY,
        UsageAggregator.Range.D7,
        UsageAggregator.Range.D30,
        UsageAggregator.Range.ALL,
    )
    Segmented(options, values.indexOf(range)) { onRange(values[it]) }
}

@Composable
private fun FilterRow(providerIds: List<String>, selected: Set<String>?, onSelected: (Set<String>?) -> Unit) {
    var sheetOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("供应商", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Card)
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                .clickable { sheetOpen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    selected == null -> "全部"
                    selected.isEmpty() -> "未选择"
                    selected.size == 1 -> Providers.displayName(selected.first())
                    else -> "已选 ${selected.size} 家"
                },
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary)
        }
    }
    if (sheetOpen) {
        ProviderFilterSheet(providerIds, selected, onSelected) { sheetOpen = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFilterSheet(
    providerIds: List<String>,
    selected: Set<String>?,
    onSelected: (Set<String>?) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Card) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("供应商筛选", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetAction("全部") {
                    onSelected(null)
                    onClose()
                }
                SheetAction("清空") {
                    onSelected(emptySet())
                    onClose()
                }
            }
            Spacer(Modifier.height(8.dp))
            if (providerIds.isEmpty()) {
                Text("还没有任何供应商数据", color = TextSecondary, fontSize = 14.sp)
            }
            providerIds.forEach { id ->
                val checked = selected == null || id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val current = selected ?: providerIds.toSet()
                            val next = if (id in current) current - id else current + id
                            onSelected(next)
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(Providers.palette[Providers.colorIndex(id)]))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Providers.displayName(id),
                        color = if (checked) TextPrimary else TextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (checked) Text("✓", color = Mint, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
internal fun NotConfigured(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("尚未连接坚果云", fontSize = 17.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("在设置中填入 WebDAV 账号与同步口令后，这里显示已同步的用量", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Purple)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text("去连接", color = dev.magpie.usage.ui.theme.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyRange(state: UsageViewModel.UiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("所选区间没有记录", fontSize = 16.sp, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        val coverage = state.result?.let { r ->
            val zone = state.zone
            buildString {
                val first = r.firstRecordAt
                val last = r.lastRecordAt
                if (first != null && last != null) {
                    append("已同步记录覆盖 ${Formatters.date(first.toEpochMilli(), zone)} 至 ${Formatters.date(last.toEpochMilli(), zone)}")
                }
            }
        }
        if (!coverage.isNullOrEmpty()) {
            Text(coverage, fontSize = 12.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun SummaryCards(result: UsageAggregator.Result, priceSource: dev.magpie.usage.data.PriceSource) {
    val t = result.totals
    val cards: List<Pair<String, @Composable () -> Unit>> = listOf(
        "Token（含缓存）" to {
            Footnote(
                "输入 ${Formatters.tokens(t.input)} · 输出 ${Formatters.tokens(t.output)} · " +
                    "缓存 ${Formatters.tokens(t.cacheRead + t.cacheWrite)}"
            )
        },
        "请求" to { RequestFootnote(t) },
        "参考费用" to { CostFootnote(t, priceSource) },
        "缓存命中率" to {
            Footnote(
                if (t.cacheUnknownCalls > 0) "${t.cacheUnknownCalls} 个请求未计入：其服务商不统计缓存"
                else "按已计缓存的请求"
            )
        },
    )
    val values = listOf(
        Formatters.tokens(t.allTokens),
        Formatters.count(t.calls),
        costValue(t),
        hitValue(t),
    )
    // 手机 2 列 × 2 行；平板/横屏 4 列 × 1 行。每行等高（IntrinsicSize.Min）。
    val columns = dev.magpie.usage.ui.rememberWidthClass().statColumns()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.indices.chunked(columns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                row.forEach { i ->
                    StatCard(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = cards[i].first,
                        value = values[i],
                    ) { cards[i].second() }
                }
            }
        }
    }
}

private fun costValue(t: UsageAggregator.Totals): String {
    val hasUnpriced = t.unpriced > 0
    return when {
        t.calls == 0L && t.input == 0L && t.output == 0L -> "0"
        t.costUsd == 0.0 && !hasUnpriced && t.credited == 0L -> "0"
        t.costUsd == 0.0 && hasUnpriced && t.unpriced > 0 -> "未计价"
        else -> Formatters.cost(t.costUsd, hasUnpriced)
    }
}

internal fun hitValue(t: UsageAggregator.Totals): String {
    val rate = t.hitRate()
    return if (rate == null) "—" else Formatters.percent(rate)
}

/** 卡片脚注：允许换行（桌面版也是多行），不再用省略号截断。 */
@Composable
private fun Footnote(text: String) {
    Text(text, fontSize = 12.sp, color = TextSecondary, maxLines = 3)
}

@Composable
private fun RequestFootnote(t: UsageAggregator.Totals) {
    // 颜色语义（视觉规范）：失败=珊瑚红，重试成功=绿，取消/其余=次文字色。
    val text = if (t.errors == 0L && t.retried == 0L && t.canceled == 0L) {
        androidx.compose.ui.text.AnnotatedString("成功 ${t.calls} 次")
    } else {
        androidx.compose.ui.text.buildAnnotatedString {
            var first = true
            fun add(s: String, style: androidx.compose.ui.text.SpanStyle? = null) {
                if (!first) append(" · ")
                first = false
                if (style != null) pushStyle(style)
                append(s)
                if (style != null) pop()
            }
            if (t.errors > 0) add("${t.errors} 次失败", androidx.compose.ui.text.SpanStyle(color = Coral))
            if (t.retried > 0) add("${t.retried} 次重试后成功", androidx.compose.ui.text.SpanStyle(color = Mint))
            if (t.canceled > 0) add("${t.canceled} 次已取消")
            if (t.errors == 0L && t.retried == 0L) add("成功 ${t.calls} 次")
        }
    }
    Text(text, fontSize = 12.sp, color = TextSecondary, maxLines = 3)
}

@Composable
private fun CostFootnote(t: UsageAggregator.Totals, priceSource: dev.magpie.usage.data.PriceSource) {
    val parts = mutableListOf<String>()
    if (t.unpriced > 0) parts.add("${t.unpriced} 次调用未计价")
    if (t.credited > 0) parts.add("${Formatters.count(t.credited)} 次计积分 %.2f credits".format(t.credits))
    val base = when {
        priceSource.lookup != null -> "牌价 models.dev${priceSource.fetchedAt.takeLast(5).let { if (it.contains('-')) " $it" else "" }}·非订阅账单"
        t.calls > 0L -> "无价格表 · 未计价"
        else -> "按已有记录估算，非订阅账单"
    }
    Text(
        (listOf(base) + parts).joinToString(" · "),
        fontSize = 12.sp,
        color = TextSecondary,
        maxLines = 3,
    )
}

@Composable
private fun StatCard(
    modifier: Modifier,
    label: String,
    value: String,
    footnote: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val icon = when (label) {
                "请求" -> Icons.Outlined.SwapVert
                "参考费用" -> Icons.Outlined.Payments
                "缓存命中率" -> Icons.Outlined.Bolt
                else -> Icons.Outlined.DataUsage
            }
            Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        footnote()
    }
}

@Composable
internal fun ChartCard(
    result: UsageAggregator.Result,
    dimension: UsageAggregator.Dimension,
    zone: java.time.ZoneId,
) {
    var metric by remember { mutableStateOf(UsageAggregator.Metric.TOKENS) }
    var selectedBucket by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(14.dp),
    ) {
        Segmented(
            options = listOf("Token", "费用", "请求"),
            selectedIndex = listOf(
                UsageAggregator.Metric.TOKENS,
                UsageAggregator.Metric.COST,
                UsageAggregator.Metric.CALLS,
            ).indexOf(metric),            onSelected = { i ->
                metric = listOf(
                    UsageAggregator.Metric.TOKENS,
                    UsageAggregator.Metric.COST,
                    UsageAggregator.Metric.CALLS,
                )[i]
                selectedBucket = null
            },
        )
        if (selectedBucket != null) {
            Spacer(Modifier.height(10.dp))
            BucketTooltip(result, selectedBucket!!, metric, dimension, zone)
        }
        Spacer(Modifier.height(12.dp))
        StackedBarChart(result, metric, selectedBucket) { col ->
            selectedBucket = if (selectedBucket == col) null else col
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StackedBarChart(
    result: UsageAggregator.Result,
    metric: UsageAggregator.Metric,
    selectedBucket: Int?,
    onTapBucket: (Int) -> Unit,
) {
    val series = result.series
    if (series.isEmpty()) {
        Text("暂无趋势数据", color = TextSecondary, fontSize = 13.sp)
        return
    }

    // Stack order: largest total first sits at the bottom, like the desktop.
    val stackIds = series.flatMap { it.providers.keys }.distinct().sortedByDescending { id ->
        series.sumOf { it.providers[id]?.allTokens ?: 0L }
    }
    val currentOnTap by androidx.compose.runtime.rememberUpdatedState(onTapBucket)
    val gridColor = CardBorder
    val selectionColor = Purple.copy(alpha = 0.10f)

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(result, metric) {
                    detectTapGestures { offset ->
                        val n = series.size
                        if (n == 0) return@detectTapGestures
                        val slot = size.width / n
                        val col = (offset.x / slot).toInt().coerceIn(0, n - 1)
                        currentOnTap(col)
                    }
                },
        ) {
            val chartTop = 8f
            val chartBottom = size.height - 20f
            val chartHeight = chartBottom - chartTop
            val maxValue = series.maxOf { p ->
                when (metric) {
                    UsageAggregator.Metric.TOKENS -> p.totals.allTokens.toDouble()
                    UsageAggregator.Metric.COST -> p.totals.costUsd
                    UsageAggregator.Metric.CALLS -> p.totals.calls.toDouble()
                }
            }.coerceAtLeast(1.0)
            val n = series.size
            val slot = size.width / n
            val barWidth = (slot * 0.55f).coerceAtMost(28.dp.toPx())
            // The tapped column gets a darker band behind its bars, like the desktop.
            if (selectedBucket != null && selectedBucket in series.indices) {
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(slot * selectedBucket!!, chartTop - 4f),
                    size = Size(slot, chartHeight + 10f),
                )
            }
            // up to 4 gridlines
            val gridLines = 4
            for (g in 0..gridLines) {
                val y = chartBottom - chartHeight * g / gridLines
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            series.forEachIndexed { i, point ->
                val valueTotal = when (metric) {
                    UsageAggregator.Metric.TOKENS -> point.totals.allTokens
                    UsageAggregator.Metric.COST -> point.totals.costUsd
                    UsageAggregator.Metric.CALLS -> point.totals.calls
                }.toDouble()
                if (valueTotal <= 0.0) return@forEachIndexed
                val x = slot * i + (slot - barWidth) / 2
                var yCursor = chartBottom
                for (id in stackIds) {
                    val t = point.providers[id] ?: continue
                    val v = when (metric) {
                        UsageAggregator.Metric.TOKENS -> t.allTokens
                        UsageAggregator.Metric.COST -> t.costUsd
                        UsageAggregator.Metric.CALLS -> t.calls
                    }.toDouble()
                    if (v <= 0.0) continue
                    val segHeight = (v / maxValue * chartHeight).toFloat()
                    drawRoundRect(
                        color = Color(Providers.palette[Providers.colorIndex(id)]),
                        topLeft = Offset(x, yCursor - segHeight),
                        size = Size(barWidth, segHeight),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                    yCursor -= segHeight
                }
            }
        }
        // x labels: sparse
        val labelEvery = when {
            series.size <= 8 -> 1
            series.size <= 16 -> 2
            series.size <= 40 -> 5
            else -> 7
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            series.forEachIndexed { i, point ->
                if (i % labelEvery == 0 || i == series.size - 1) {
                    Text(point.label, fontSize = 10.sp, color = TextSecondary)
                } else {
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    result: UsageAggregator.Result,
    dimension: UsageAggregator.Dimension,
    onDimension: (UsageAggregator.Dimension) -> Unit,
) {
    var metric by remember { mutableStateOf(UsageAggregator.Metric.TOKENS) }
    val dimensions = UsageAggregator.Dimension.entries
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(14.dp),
    ) {
        Text("用量拆分", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Segmented(
            options = dimensions.map { it.title },
            selectedIndex = dimensions.indexOf(dimension),
            fontSize = 13.sp,
            scrollable = true,
            onSelected = { i -> onDimension(dimensions[i]) },
        )
        Spacer(Modifier.height(6.dp))
        // 该板块自己的指标切换：列表与占比条跟随它重排。
        Segmented(
            options = listOf("Token", "请求", "费用"),
            selectedIndex = listOf(
                UsageAggregator.Metric.TOKENS,
                UsageAggregator.Metric.CALLS,
                UsageAggregator.Metric.COST,
            ).indexOf(metric),
            onSelected = { i ->
                metric = listOf(
                    UsageAggregator.Metric.TOKENS,
                    UsageAggregator.Metric.CALLS,
                    UsageAggregator.Metric.COST,
                )[i]
            },
        )
        Spacer(Modifier.height(10.dp))
        if (result.providers.isEmpty()) {
            Text("该范围内没有数据", color = TextSecondary, fontSize = 13.sp)
        }
        val sorted = result.providers.sortedWith(
            compareByDescending<UsageAggregator.ProviderShare> { metricValueOf(it.totals, metric) }
                .thenByDescending { it.totals.calls }
                .thenBy { it.id }
        )
        val grand = sorted.sumOf { metricValueOf(it.totals, metric) }.coerceAtLeast(1e-9)
        sorted.forEach { share ->
            BreakdownRow(share, dimension, metric, grand)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun BreakdownRow(
    share: UsageAggregator.ProviderShare,
    dimension: UsageAggregator.Dimension,
    metric: UsageAggregator.Metric,
    grand: Double,
) {
    val value = metricValueOf(share.totals, metric)
    val color = Color(Providers.palette[Providers.colorIndex(dimension.colorKey(share.id))])
    val fraction = (value / grand).toFloat().coerceIn(0.004f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                dimension.display(share.id),
                fontSize = 15.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(metricText(value, metric), fontSize = 15.sp, color = TextPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CardBorder),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.height(4.dp))
        // 明细行：仅"n 次失败"用珊瑚红，其余为次文字色（对齐桌面端）。
        val details = androidx.compose.ui.text.buildAnnotatedString {
            var first = true
            fun add(s: String, style: androidx.compose.ui.text.SpanStyle? = null) {
                if (!first) append(" · ")
                first = false
                if (style != null) pushStyle(style)
                append(s)
                if (style != null) pop()
            }
            add("${share.totals.calls} 次请求")
            if (metric != UsageAggregator.Metric.TOKENS) add("Token ${Formatters.tokens(share.totals.allTokens)}")
            val pct = (value / grand * 100).toInt()
            if (pct in 1..99) add("$pct%")
            if (share.totals.errors > 0) add("${share.totals.errors} 次失败", androidx.compose.ui.text.SpanStyle(color = Coral))
            if (share.totals.unpriced > 0) add("${share.totals.unpriced} 次未计价")
            if (share.totals.credited > 0) add("%.2f credits".format(share.totals.credits))
        }
        Text(
            details,
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图表点击浮层：选中桶的分项与合计（对应桌面端 tooltip）。 */
@Composable
private fun BucketTooltip(
    result: UsageAggregator.Result,
    bucketIndex: Int,
    metric: UsageAggregator.Metric,
    dimension: UsageAggregator.Dimension,
    zone: java.time.ZoneId,
) {
    val point = result.series.getOrNull(bucketIndex) ?: return
    val entries = point.providers.entries
        .map { (id, t) -> id to metricValueOf(t, metric) }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
    val shown = entries.take(5)
    val rest = entries.drop(5)
    val total = metricValueOf(point.totals, metric)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(dev.magpie.usage.ui.theme.SoftSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            tooltipTitle(point, result.bucket, zone),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        shown.forEach { (id, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(Providers.palette[Providers.colorIndex(dimension.colorKey(id))]))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dimension.display(id),
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(metricText(value, metric), fontSize = 13.sp, color = TextPrimary)
            }
            Spacer(Modifier.height(3.dp))
        }
        if (rest.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(CardBorder))
                Spacer(Modifier.width(8.dp))
                Text("其他 ${rest.size} 项", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text(metricText(rest.sumOf { it.second }, metric), fontSize = 13.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(3.dp))
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("合计", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(
                metricText(total, metric) + " · ${point.totals.calls} 个请求",
                fontSize = 13.sp,
                color = TextSecondary,
            )
        }
    }
}

private fun tooltipTitle(point: UsageAggregator.SeriesPoint, bucket: String, zone: java.time.ZoneId): String {
    val d = point.time.atZone(zone)
    val date = "${d.year}/${d.monthValue}/${d.dayOfMonth}"
    return when (bucket) {
        "hour" -> "$date ${"%02d:%02d".format(d.hour, d.minute)}"
        else -> date
    }
}

private fun metricValueOf(t: UsageAggregator.Totals, metric: UsageAggregator.Metric): Double = when (metric) {
    UsageAggregator.Metric.TOKENS -> t.allTokens.toDouble()
    UsageAggregator.Metric.COST -> t.costUsd
    UsageAggregator.Metric.CALLS -> t.calls.toDouble()
}

private fun metricText(value: Double, metric: UsageAggregator.Metric): String = when (metric) {
    UsageAggregator.Metric.TOKENS -> Formatters.tokens(value.toLong())
    UsageAggregator.Metric.COST -> Formatters.cost(value, false)
    UsageAggregator.Metric.CALLS -> Formatters.count(value.toLong())
}

@Composable
private fun CoverageNote(state: UsageViewModel.UiState, result: UsageAggregator.Result) {
    val zone = state.zone
    val coverage = buildString {
        val first = result.firstRecordAt
        val last = result.lastRecordAt
        if (first != null && last != null) {
            append("已同步记录：${Formatters.date(first.toEpochMilli(), zone)} 至 ${Formatters.date(last.toEpochMilli(), zone)}；")
        }
        append("统计全部已同步记录，可能延迟更新")
    }
    Text(coverage, fontSize = 12.sp, color = TextSecondary)
}
