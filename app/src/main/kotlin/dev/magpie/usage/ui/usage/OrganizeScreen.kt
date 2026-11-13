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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magpie.usage.domain.Providers
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Page
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary

/**
 * 整理：供应商排序、是否展示，以及每个供应商下各账号是否列出。
 * 隐藏只影响概览页显示，用量页与统计照常；被隐藏账号的数据仍计入
 * 「全部 N 个账号」合并条。
 */
@Composable
fun OrganizeScreen(
    viewModel: UsageViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val organized = remember(state.quotaGroups, state.organize) {
        val all = state.quotaGroups
        val byId = all.associateBy { it.provider }
        val ordered = state.organize.order.mapNotNull { byId[it] } +
            all.filter { it.provider !in state.organize.order.toSet() }
        ordered
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Page)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "整理", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = TextPrimary, modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Card)
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text("完成", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            }
        }
        Text(
            "隐藏只影响概览页；用量页与统计照常。账号不列出时其数据仍计入供应商合并条。",
            fontSize = 12.sp,
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                .clickable { viewModel.resetProviderOrder() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text("按默认顺序重排", fontSize = 13.sp, color = TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (organized.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("还没有供应商数据", color = TextSecondary, fontSize = 14.sp)
                }
            }
            organized.forEachIndexed { index, group ->
                val hidden = group.provider in state.organize.hidden
                ProviderOrganizeCard(
                    provider = group.provider,
                    accounts = group.accounts,
                    hidden = hidden,
                    hiddenAccounts = state.organize.hiddenAccounts,
                    canUp = index > 0,
                    canDown = index < organized.size - 1,
                    onUp = { viewModel.moveProvider(group.provider, -1) },
                    onDown = { viewModel.moveProvider(group.provider, +1) },
                    onToggleHidden = { viewModel.setHidden(group.provider, !hidden) },
                    onToggleAccount = { user, show ->
                        viewModel.setAccountHidden(group.provider, user, !show)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderOrganizeCard(
    provider: String,
    accounts: List<dev.magpie.usage.domain.QuotaOverviewBuilder.AccountView>,
    hidden: Boolean,
    hiddenAccounts: Set<String>,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleAccount: (user: String, show: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(Providers.palette[Providers.colorIndex(provider)])),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                Providers.displayName(provider),
                fontSize = 15.sp,
                color = if (hidden) TextSecondary else TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ArrowButton(Icons.Filled.ArrowUpward, "上移", canUp, onUp)
            Spacer(Modifier.width(4.dp))
            ArrowButton(Icons.Filled.ArrowDownward, "下移", canDown, onDown)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardBorder.copy(alpha = 0.5f))
                    .clickable(onClick = onToggleHidden)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(if (hidden) "显示" else "隐藏", fontSize = 12.sp, color = TextPrimary)
            }
        }
        if (accounts.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text("在概览里列出的账号", fontSize = 11.sp, color = TextSecondary)
            accounts.forEach { account ->
                val show = accountKey(provider, account.user) !in hiddenAccounts
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(Providers.palette[Providers.colorIndex(account.user)])),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        account.user.ifEmpty { "默认账号" },
                        fontSize = 13.sp,
                        color = if (show) TextPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = show,
                        onCheckedChange = { onToggleAccount(account.user, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Purple),
                        modifier = Modifier.size(width = 44.dp, height = 26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBorder.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}
