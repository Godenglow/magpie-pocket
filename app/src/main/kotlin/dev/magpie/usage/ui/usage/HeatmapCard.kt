package dev.magpie.usage.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magpie.usage.domain.Formatters
import dev.magpie.usage.domain.UsageHeatmap
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.ZoneId

/**
 * 用量热力图：最近 53 周的每日用量。周一在第一行，每列一周；横向滚动，
 * 点选一天看精确数值。配色按所选指标的最大值开方分级（0–4）。
 */
@Composable
fun HeatmapCard(
    heatmap: UsageHeatmap.Heatmap?,
    zone: ZoneId,
) {
    var metric by remember { mutableStateOf(UsageHeatmap.Metric.TOKENS) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("用量热力图", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("最近 53 周的每日用量", fontSize = 12.sp, color = TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Segmented(
            options = listOf("Token", "请求", "费用"),
            selectedIndex = listOf(
                UsageHeatmap.Metric.TOKENS,
                UsageHeatmap.Metric.CALLS,
                UsageHeatmap.Metric.COST,
            ).indexOf(metric),
            onSelected = { i ->
                metric = listOf(
                    UsageHeatmap.Metric.TOKENS,
                    UsageHeatmap.Metric.CALLS,
                    UsageHeatmap.Metric.COST,
                )[i]
            },
        )
        Spacer(Modifier.height(10.dp))

        if (heatmap == null) {
            Text("暂无数据", fontSize = 13.sp, color = TextSecondary)
        } else {
            HeatmapGrid(heatmap, metric, selected) { date -> selected = if (selected == date) null else date }
            Spacer(Modifier.height(8.dp))
            SelectedDayLine(heatmap, selected, metric, zone)
        }
    }
}

@Composable
private fun HeatmapGrid(
    heatmap: UsageHeatmap.Heatmap,
    metric: UsageHeatmap.Metric,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val pitch = 16.dp
    val cell = 14.dp
    val monthRow = 18.dp
    val gridWidth = pitch * heatmap.weeks.size
    val gridHeight = pitch * 7
    val max = UsageHeatmap.maxOf(heatmap, metric)
    val textMeasurer = rememberTextMeasurer()
    val levels = (0..4).map { levelColor(it) }
    val selectionColor = Purple
    val labelStyle = TextStyle(fontSize = 10.sp, color = TextSecondary)

    Row(verticalAlignment = Alignment.Top) {
        // Weekday labels, fixed on the left: rows 0/2/4/6. The row box is
        // pitch-tall so the glyphs never clip vertically.
        Column(
            modifier = Modifier
                .width(36.dp)
                .height(monthRow + gridHeight),
        ) {
            Spacer(Modifier.height(monthRow))
            listOf("周一", null, "周三", null, "周五", null, "周日").forEach { label ->
                Box(Modifier.height(pitch), contentAlignment = Alignment.CenterStart) {
                    if (label != null) {
                        Text(
                            label,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
        ) {
            Canvas(
                modifier = Modifier
                    .width(gridWidth)
                    .height(monthRow + gridHeight)
                    .pointerInput(heatmap, metric) {
                        detectTapGestures { offset ->
                            val col = (offset.x / pitch.toPx()).toInt()
                            val row = ((offset.y - monthRow.toPx()) / pitch.toPx()).toInt()
                            if (col in heatmap.weeks.indices && row in 0..6) {
                                onSelect(heatmap.weeks[col].monday.plusDays(row.toLong()))
                            }
                        }
                    },
            ) {
                val pitchPx = pitch.toPx()
                val cellPx = cell.toPx()
                val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

                // Month labels on their columns.
                heatmap.monthLabels.forEach { (col, label) ->
                    val layout = textMeasurer.measure(label, labelStyle)
                    val x = col * pitchPx
                    if (x + layout.size.width < size.width) {
                        drawText(
                            layout,
                            topLeft = Offset(x, (monthRow.toPx() - layout.size.height) / 2f),
                        )
                    }
                }

                // Cells.
                heatmap.weeks.forEachIndexed { col, week ->
                    week.days.forEachIndexed { row, cellData ->
                        val value = UsageHeatmap.metricValue(cellData, metric)
                        val level = UsageHeatmap.level(value, max)
                        val topLeft = Offset(col * pitchPx, monthRow.toPx() + row * pitchPx)
                        drawRoundRect(
                            color = levels[level],
                            topLeft = topLeft,
                            size = Size(cellPx, cellPx),
                            cornerRadius = radius,
                        )
                        if (selected == cellData.date) {
                            drawRoundRect(
                                color = selectionColor,
                                topLeft = topLeft,
                                size = Size(cellPx, cellPx),
                                cornerRadius = radius,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Legend()
}

@Composable
private fun levelColor(level: Int): Color = when (level) {
    0 -> dev.magpie.usage.ui.theme.SoftSurface
    1 -> Purple.copy(alpha = 0.30f)
    2 -> Purple.copy(alpha = 0.55f)
    3 -> Purple.copy(alpha = 0.80f)
    else -> Purple
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("少", fontSize = 10.sp, color = TextSecondary)
        listOf(0, 1, 2, 3, 4).forEach { level ->
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor(level)),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text("多", fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun SelectedDayLine(
    heatmap: UsageHeatmap.Heatmap,
    selected: LocalDate?,
    metric: UsageHeatmap.Metric,
    zone: ZoneId,
) {
    if (selected == null) {
        Text(
            "点选一格查看当天的精确数值",
            fontSize = 11.sp,
            color = TextSecondary,
        )
        return
    }
    val cell = heatmap.weeks
        .firstOrNull { w -> !selected.isBefore(w.monday) && selected.isBefore(w.monday.plusDays(7)) }
        ?.days?.firstOrNull { it.date == selected }
    if (cell == null) {
        Text(Formatters.date(selected.atStartOfDay(zone).toInstant().toEpochMilli(), zone), fontSize = 11.sp, color = TextSecondary)
        return
    }
    val t = cell.totals
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (UsageHeatmap.level(UsageHeatmap.metricValue(cell, metric), UsageHeatmap.maxOf(heatmap, metric)) > 0) Purple else CardBorder))
        Spacer(Modifier.width(6.dp))
        Text(
            "${selected.monthValue}月${selected.dayOfMonth}日 · Token ${Formatters.tokens(t.allTokens)} · 请求 ${t.calls}" +
                if (t.costUsd > 0.0) " · ${Formatters.cost(t.costUsd, t.unpriced > 0)}" else "",
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
