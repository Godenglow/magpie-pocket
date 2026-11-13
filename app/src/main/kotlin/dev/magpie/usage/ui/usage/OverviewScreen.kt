package dev.magpie.usage.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary

/**
 * 概览 = 额度（托盘样式）。用量在「用量」页看，这里不重复。
 * 供应商排序/隐藏由底部「整理」控制，对额度卡生效。
 */
@Composable
fun OverviewScreen(
    viewModel: UsageViewModel,
    onOpenSettings: () -> Unit,
    onOpenOrganize: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        DashboardHeader("概览", state.syncState == UsageViewModel.SyncState.CHECKING,
            onRefresh = { viewModel.refresh() }, onSettings = onOpenSettings,
            onTheme = viewModel::setTheme)
        Spacer(Modifier.height(4.dp))
        SyncStatusLine(state)
        Spacer(Modifier.height(12.dp))

        if (state.settings?.configured != true) {
            NotConfigured(onOpenSettings)
        } else {
            if (state.quotaGroups.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无额度数据", fontSize = 16.sp, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "桌面端 Magpie 会把各供应商的额度读数共享到云端；\n同步后这里按托盘样式显示剩余额度",
                        fontSize = 12.sp,
                        color = dev.magpie.usage.ui.theme.TextSecondary,
                    )
                }
            } else {
                val usedMode = remember { mutableStateOf(false) }
                // 全局「剩余/已用」切换放在顶部一行，供应商行只留名字与账号数，
                // 避免每个供应商都挤一个胶囊（小屏/窄屏尤其明显）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "额度",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    ModeToggle(usedMode.value) { usedMode.value = !usedMode.value }
                }
                Spacer(Modifier.height(8.dp))
                QuotaTraySection(
                    groups = state.quotaGroups,
                    zone = state.zone,
                    usedMode = usedMode.value,
                    onToggleMode = { usedMode.value = !usedMode.value },
                    hiddenAccounts = state.organize.hiddenAccounts,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Card)
                        .border(1.dp, CardBorder, RoundedCornerShape(999.dp))
                        .clickable(onClick = onOpenOrganize)
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                ) {
                    Text("整理", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "额度来自桌面共享的额度历史，只有百分比读数；余额、积分数等绝对数值是桌面实时数据。" +
                    "整理可排序、隐藏供应商；隐藏只影响本页，用量页与统计照常",
                fontSize = 11.sp,
                color = dev.magpie.usage.ui.theme.TextSecondary,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
