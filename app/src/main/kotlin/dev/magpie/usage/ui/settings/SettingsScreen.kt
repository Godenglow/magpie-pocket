package dev.magpie.usage.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magpie.usage.ui.theme.Card
import dev.magpie.usage.ui.theme.CardBorder
import dev.magpie.usage.ui.theme.Coral
import dev.magpie.usage.ui.theme.Mint
import dev.magpie.usage.ui.theme.Page
import dev.magpie.usage.ui.theme.Purple
import dev.magpie.usage.ui.theme.TextPrimary
import dev.magpie.usage.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
    var clearDialog by remember { mutableStateOf(false) }
    var clearMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Page)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
            }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        Spacer(Modifier.height(8.dp))

        SectionCard {
            Text("外观", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text("选择适合你的显示方式", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(16.dp))
            val themeMode by viewModel.getApplication<dev.magpie.usage.MagpieApp>()
                .settingsStore.settings.collectAsState(initial = state.settings)
            val modes = listOf("system", "light", "dark")
            dev.magpie.usage.ui.usage.Segmented(
                options = listOf("跟随系统", "浅色", "深色"),
                selectedIndex = modes.indexOf(themeMode?.themeMode ?: state.themeMode),
                onSelected = { viewModel.setTheme(modes[it]) },
            )
        }
        Spacer(Modifier.height(16.dp))

        SectionCard {
            Text("坚果云 WebDAV", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("连接后在本机解密用量数据；保存并同步后，返回用量页即可看到数据", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            Field(label = "服务器地址", value = state.serverUrl) { v -> viewModel.update { it.copy(serverUrl = v) } }
            Field(label = "账号", value = state.account, placeholder = "you@example.com") { v -> viewModel.update { it.copy(account = v) } }
            SecretField(
                label = "应用密码",
                value = state.appPassword,
                placeholder = "坚果云生成的应用密码，不是登录密码",
            ) { v -> viewModel.update { it.copy(appPassword = v) } }
            Field(label = "用量目录", value = state.usageDir, placeholder = "/magpie/usage/") { v -> viewModel.update { it.copy(usageDir = v) } }
            Text("相对 WebDAV 根的完整目录；若桌面端配置了子目录，请包含它", fontSize = 11.sp, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            SecretField(
                label = "Magpie 同步口令",
                value = state.syncPassphrase,
                placeholder = "与桌面端共享用量口令一致",
                helper = "同步口令用于解密 .magpie-usage 文件，与应用密码不同",
            ) { v -> viewModel.update { it.copy(syncPassphrase = v) } }
            Spacer(Modifier.height(12.dp))
            ButtonRow(
                primaryLabel = "测试连接",
                primaryAction = { viewModel.testConnection() },
                secondaryLabel = "保存并同步",
                secondaryAction = { viewModel.saveAndSync() },
            )
            testResultText(state)
            if (state.saveMessage != null) {
                Text(state.saveMessage!!, fontSize = 12.sp, color = Mint)
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            IntervalRow(state.foregroundMinutes) { v -> viewModel.update { it.copy(foregroundMinutes = v) } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台尽力刷新", fontSize = 15.sp, color = TextPrimary)
                    Text("由系统调度（最短 15 分钟，可能被省电策略延后），打开应用会主动检查", fontSize = 11.sp, color = TextSecondary)
                }
                Switch(
                    checked = state.backgroundEnabled,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(backgroundEnabled = v) }
                        dev.magpie.usage.sync.UsageWorker.schedule(
                            (viewModel.getApplication<android.app.Application>()), v
                        )
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Purple),
                )
            }
            Spacer(Modifier.height(8.dp))
            TimeZoneRow(state.timeZoneMode) { v -> viewModel.update { it.copy(timeZoneMode = v) } }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("显示", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("只影响本机展示，不改变统计与已同步数据", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                label = "货币",
                options = listOf("usd" to "$ 美元", "cny" to "¥ 人民币"),
                selected = state.currency,
            ) { v -> viewModel.setCurrency(v) }
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                label = "数字单位",
                options = listOf("en" to "英文（K / M / B）", "cn" to "中文（万 / 亿）"),
                selected = state.numberUnit,
            ) { v -> viewModel.setNumberUnit(v) }
            if (state.currency == "cny") {
                Spacer(Modifier.height(10.dp))
                Text("汇率（1 美元 = ? 人民币）", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = state.exchangeRate,
                        onValueChange = { viewModel.setExchangeRate(it) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = TextPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "牌价只有美元，人民币按你填的汇率折算（不是实时汇率）",
                    fontSize = 11.sp,
                    color = TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("价格表", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("参考费用按公开牌价估算；来源 models.dev，可随时更新", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Purple, RoundedCornerShape(10.dp))
                        .clickable { viewModel.updatePriceTable() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (state.priceUpdating) "更新中…" else "更新价格表", color = Purple, fontSize = 14.sp)
                }
                if (state.priceUpdating) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = Purple)
                }
                Text(
                    when {
                        state.priceSource.lookup == null -> "无价格表"
                        state.priceSource.source == "已更新" -> "更新于 ${state.priceSource.fetchedAt}"
                        else -> "内置快照 ${state.priceSource.fetchedAt}"
                    },
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
            if (state.priceMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(state.priceMessage!!, fontSize = 12.sp, color = if (state.priceMessage!!.startsWith("已更新")) Mint else Coral)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("阶段 0 数据验证", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("只读盘点云端与本机缓存：覆盖范围、设备、来源、未计价比例", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Purple, RoundedCornerShape(10.dp))
                        .clickable { viewModel.runDiagnostics() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (state.diagnosticsRunning) "验证中…" else "运行验证", color = Purple, fontSize = 14.sp)
                }
                if (state.diagnosticsRunning) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = Purple)
                }
            }
            if (state.diagnostics != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.diagnostics!!,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("本机归档", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("远端过期不会自动删除本机已导入的历史；清除是显式操作，不影响云端与凭据", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Coral, RoundedCornerShape(10.dp))
                    .clickable { clearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("清除本机历史归档", color = Coral, fontSize = 14.sp)
            }
            if (clearMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(clearMessage!!, fontSize = 12.sp, color = Mint)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            Text("关于", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("magpie 用量看板 v$versionName · 协议对齐 yetone/magpie @ 6acf5ee（2026-11-12）", fontSize = 12.sp, color = TextSecondary)
            Text("所有下载只读；凭据保存在 Android Keystore，不进入备份与日志", fontSize = 12.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清除本机历史归档？") },
            text = { Text("将删除手机上已导入的全部用量记录。云端文件不受影响，重新同步会再次导入。") },
            confirmButton = {
                TextButton(onClick = {
                    clearDialog = false
                    viewModel.clearLocalArchive { msg -> clearMessage = msg }
                }) { Text("清除", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } },
            containerColor = Card,
        )
    }
}

@Composable
private fun testResultText(state: SettingsViewModel.UiState) {
    when (state.testState) {
        SettingsViewModel.TestState.RUNNING -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = Purple)
                Spacer(Modifier.width(8.dp))
                Text("正在测试…", fontSize = 12.sp, color = TextSecondary)
            }
        }
        SettingsViewModel.TestState.OK -> {
            Spacer(Modifier.height(8.dp))
            Text("✓ ${state.testMessage ?: "已连接"}", fontSize = 12.sp, color = Mint)
        }
        SettingsViewModel.TestState.FAILED -> {
            Spacer(Modifier.height(8.dp))
            Text("✗ ${state.testMessage ?: "连接失败"}", fontSize = 12.sp, color = Coral)
        }
        else -> {}
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)

            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue,
        label = { Text(label) }, placeholder = { Text(placeholder ?: "") },
        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SecretField(label: String, value: String, placeholder: String? = null,
    helper: String? = null, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onValue,
        label = { Text(label) }, placeholder = { Text(placeholder ?: "") },
        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码")
            }
        },
        supportingText = if (helper != null) ({ Text(helper) }) else null,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ButtonRow(primaryLabel: String, primaryAction: () -> Unit,
    secondaryLabel: String, secondaryAction: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = primaryAction, modifier = Modifier.weight(1f)) { Text(primaryLabel) }
        Button(onClick = secondaryAction, modifier = Modifier.weight(1f)) { Text(secondaryLabel) }
    }
}

/** 两段选择（货币、数字单位），样式同桌面设置页的胶囊切换。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Page)
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp)),
        ) {
            options.forEach { (value, text) ->
                val on = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) CardBorder else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text,
                        fontSize = 13.sp,
                        color = if (on) TextPrimary else TextSecondary,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalRow(minutes: Int, onMinutes: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("前台检查间隔", fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("$minutes 分钟", fontSize = 14.sp, color = TextPrimary)
                Text(" ▾", fontSize = 12.sp, color = TextSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Card) {
                listOf(1, 3, 5, 15).forEach { v ->
                    DropdownMenuItem(
                        text = { Text("$v 分钟", color = TextPrimary) },
                        onClick = {
                            onMinutes(v)
                            open = false
                        },
                    )
                }
            }
        }
    }
    Text("仅指手机检查云端；桌面用量约每 15 分钟上传一次", fontSize = 11.sp, color = TextSecondary)
}

@Composable
private fun TimeZoneRow(mode: String, onMode: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("统计时区", fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(if (mode == "system") "系统时区" else "Asia/Shanghai", fontSize = 14.sp, color = TextPrimary)
                Text(" ▾", fontSize = 12.sp, color = TextSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Card) {
                DropdownMenuItem(
                    text = { Text("Asia/Shanghai（默认）", color = TextPrimary) },
                    onClick = {
                        onMode("shanghai")
                        open = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("系统时区", color = TextPrimary) },
                    onClick = {
                        onMode("system")
                        open = false
                    },
                )
            }
        }
    }
    Text("今天/7 天/30 天按所选时区的自然日划分", fontSize = 11.sp, color = TextSecondary)
}
