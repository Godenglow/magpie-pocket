package dev.magpie.usage.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 屏幕适配：内容宽度上限 + 居中。手机上占满宽度；平板上不把卡片拉成
 * 一条过宽的横幅，而是收在 720dp 内居中（列表类页面保持可读行宽）。
 */
@Composable
fun AdaptiveContent(
    maxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

/**
 * 布局档位：按可用宽度分成 compact / medium / expanded。
 * 用来决定一行放几列（统计卡、额度环、拆分列表）。
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWidthClass(): WidthClass {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    return when {
        width < 600.dp -> WidthClass.COMPACT
        width < 840.dp -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
}

/** 一行放几列：手机 2 列、大屏 4 列（统计卡）。 */
fun WidthClass.statColumns(): Int = when (this) {
    WidthClass.COMPACT -> 2
    WidthClass.MEDIUM -> 2
    WidthClass.EXPANDED -> 4
}
/** 拆分列表在宽屏上可以两列并排。 */
fun WidthClass.listColumns(): Int = when (this) {
    WidthClass.COMPACT -> 1
    WidthClass.MEDIUM -> 1
    WidthClass.EXPANDED -> 2
}

/** 额度圆环每行几个：手机 2 个，平板/横屏 4 个（一行放完）。 */
fun WidthClass.quotaRingColumns(): Int = when (this) {
    WidthClass.COMPACT -> 2
    WidthClass.MEDIUM -> 4
    WidthClass.EXPANDED -> 4
}
