package dev.magpie.usage.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.magpie.usage.MagpieApp
import dev.magpie.usage.core.pack.UsageFileName
import dev.magpie.usage.data.SyncSettings
import dev.magpie.usage.data.settingsSnapshot
import dev.magpie.usage.sync.CredentialKeys as CK
import dev.magpie.usage.sync.UsageRepository
import dev.magpie.usage.sync.UsageWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The connection page's state. Passwords live in the Keystore-backed
 * CredentialStore; the form holds them only while being typed.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    enum class TestState { IDLE, RUNNING, OK, FAILED }

    data class UiState(
        val settings: SyncSettings? = null,
        val serverUrl: String = "https://dav.jianguoyun.com/dav/",
        val account: String = "",
        val appPassword: String = "",
        val usageDir: String = "/magpie/usage/",
        val syncPassphrase: String = "",
        val foregroundMinutes: Int = 3,
        val backgroundEnabled: Boolean = true,
        val timeZoneMode: String = "shanghai",
        val themeMode: String = "system",
        val numberUnit: String = "cn",
        val currency: String = "usd",
        val exchangeRate: String = "7.0",
        val testState: TestState = TestState.IDLE,
        val testMessage: String? = null,
        val saveMessage: String? = null,
        val diagnostics: String? = null,
        val diagnosticsRunning: Boolean = false,
        val priceSource: dev.magpie.usage.data.PriceSource = dev.magpie.usage.data.PriceSource.EMPTY,
        val priceUpdating: Boolean = false,
        val priceMessage: String? = null,
    )

    private val magpie = app as MagpieApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = magpie.settingsStore.settingsSnapshot()
            _state.value = _state.value.copy(
                settings = settings,
                serverUrl = settings.serverUrl,
                account = settings.account,
                usageDir = settings.usageDir,
                foregroundMinutes = settings.foregroundMinutes,
                backgroundEnabled = settings.backgroundEnabled,
                timeZoneMode = settings.timeZoneMode,
                themeMode = settings.themeMode,
                numberUnit = settings.numberUnit,
                currency = settings.currency,
                exchangeRate = settings.exchangeRate.toString(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            magpie.priceSource().collect { source ->
                _state.value = _state.value.copy(priceSource = source)
            }
        }
    }

    fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    /** 显示偏好：立即写回并让 Formatters 生效（不用点保存）。 */
    private fun persistDisplay() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            magpie.settingsStore.saveDisplay(
                numberUnit = s.numberUnit,
                currency = s.currency,
                exchangeRate = s.exchangeRate.toDoubleOrNull() ?: 7.0,
            )
        }
    }

    fun setTheme(mode: String) {
        _state.value = _state.value.copy(themeMode = mode)
        viewModelScope.launch { magpie.settingsStore.saveTheme(mode) }
    }

    fun setCurrency(value: String) {
        _state.value = _state.value.copy(currency = value)
        persistDisplay()
    }

    fun setNumberUnit(value: String) {
        _state.value = _state.value.copy(numberUnit = value)
        persistDisplay()
    }

    fun setExchangeRate(text: String) {
        if (text.isNotEmpty() && text.count { it == '.' } > 1) return
        _state.value = _state.value.copy(exchangeRate = text.filter { it.isDigit() || it == '.' })
        persistDisplay()
    }

    /**
     * 测试连接：列出目录（成功=能连、能认证、目录在），并随机抽一个最新的
     * 用量文件走完整解密验证 —— HTTP 200 不等于能解密。
     */
    fun testConnection() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = s.copy(testState = TestState.RUNNING, testMessage = null)
            try {
                // Test with the FORM's values only: nothing is saved until
                // "保存并同步" is pressed, so testing a wrong password cannot
                // overwrite a connection that worked.
                val account = s.account.trim()
                val password = s.appPassword.ifEmpty {
                    magpie.credentialStore.load(CK.APP_PASSWORD) ?: ""
                }
                val passphrase = s.syncPassphrase.ifEmpty {
                    magpie.credentialStore.load(CK.SYNC_PASSPHRASE) ?: ""
                }
                if (account.isEmpty()) throw IllegalArgumentException("请填写账号")
                if (password.isEmpty()) throw IllegalArgumentException("请填写应用密码")
                if (passphrase.isEmpty()) throw IllegalArgumentException("请填写同步口令")
                val client = magpie.clientFor(s.serverUrl, account, password)
                val repo = UsageRepository(
                    { _, _ -> client },
                    magpie.store(),
                    dev.magpie.usage.sync.FixedCredentials(account, password, passphrase),
                )
                val files = repo.inventory(s.usageDir)
                val usageFiles = files.filter { UsageFileName.parse(it.name) != null }
                    .sortedByDescending { UsageFileName.parse(it.name)!!.day }
                if (usageFiles.isEmpty()) {
                    _state.value = _state.value.copy(
                        testState = TestState.FAILED,
                        testMessage = "连接成功，但目录里没有 .magpie-usage 文件：检查目录路径及 Magpie 的共享用量开关",
                    )
                    return@launch
                }
                // The newest file proves the typed passphrase opens it.
                val newest = usageFiles.first()
                val calls = repo.verifySample(s.usageDir, newest.name)
                _state.value = _state.value.copy(
                    testState = TestState.OK,
                    testMessage = "已连接 · 发现 ${usageFiles.size} 个用量文件，已验证最新文件可解密（$calls 条记录）· 点「保存并同步」才会保存",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    testState = TestState.FAILED,
                    testMessage = e.message ?: "连接失败",
                )
            }
        }
    }

    private suspend fun saveCredentialsOnly() = magpie.withConnectionGate {
        // The gate is shared with pinConnection: a sync pass takes its
        // settings+credentials snapshot under the same lock, so it can never
        // see the folder of one connection and the password of another.
        val s = _state.value
        magpie.credentialStore.save(CK.ACCOUNT, s.account.trim())
        if (s.appPassword.isNotEmpty()) {
            magpie.credentialStore.save(CK.APP_PASSWORD, s.appPassword)
        }
        if (s.syncPassphrase.isNotEmpty()) {
            magpie.credentialStore.save(CK.SYNC_PASSPHRASE, s.syncPassphrase)
        }
        magpie.settingsStore.save(
            serverUrl = s.serverUrl,
            account = s.account,
            usageDir = s.usageDir,
            foregroundMinutes = s.foregroundMinutes,
            backgroundEnabled = s.backgroundEnabled,
            timeZoneMode = s.timeZoneMode,
            numberUnit = s.numberUnit,
            currency = s.currency,
            exchangeRate = s.exchangeRate.toDoubleOrNull() ?: 7.0,
        )
    }

    fun saveAndSync() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (s.appPassword.isEmpty() && magpie.credentialStore.load(CK.APP_PASSWORD).isNullOrEmpty()) {
                    _state.value = _state.value.copy(saveMessage = "请填写应用密码")
                    return@launch
                }
                if (s.syncPassphrase.isEmpty() && magpie.credentialStore.load(CK.SYNC_PASSPHRASE).isNullOrEmpty()) {
                    _state.value = _state.value.copy(saveMessage = "请填写同步口令")
                    return@launch
                }
                saveCredentialsOnly()
                magpie.settingsStore.settingsSnapshot()
                UsageWorker.schedule(magpie, s.backgroundEnabled)
                magpie.sync().refreshNow(force = true)
                _state.value = _state.value.copy(
                    saveMessage = magpie.lastSyncOutcome?.failureMessage
                        ?.let { "已保存，但部分文件未导入：$it" }
                        ?: "已保存并开始同步",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(saveMessage = "已保存，但首次同步失败：${e.message}")
            }
        }
    }

    /** 阶段 0 数据验证：只读盘点云端与本机缓存，输出覆盖与可恢复指标。 */
    fun runDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(diagnosticsRunning = true)
            val sb = StringBuilder()
            try {
                val store = magpie.store()
                val files = store.files()
                val rows = store.allCalls()
                sb.appendLine("—— 阶段 0 数据验证（本机缓存）——")
                sb.appendLine("已导入文件数：${files.size}")
                val computers = files.map { it.computerId }.distinct()
                sb.appendLine("设备（computer id）：${if (computers.isEmpty()) "无" else computers.joinToString()}")
                val days = files.map { it.day }.filter { it.isNotEmpty() }.sorted()
                if (days.isNotEmpty()) {
                    sb.appendLine("文件日期覆盖：${days.first()} 至 ${days.last()}（共 ${days.size} 个自然日）")
                }
                sb.appendLine("本机缓存记录数：${rows.size}")
                val first = rows.minOfOrNull { it.t }
                val last = rows.maxOfOrNull { it.t }
                if (first != null && last != null) {
                    val zone = java.time.ZoneId.of("Asia/Shanghai")
                    val f = java.time.Instant.ofEpochMilli(first).atZone(zone)
                    val l = java.time.Instant.ofEpochMilli(last).atZone(zone)
                    sb.appendLine("最早记录：${f.toLocalDate()} ${"%02d:%02d".format(f.hour, f.minute)}")
                    sb.appendLine("最新记录：${l.toLocalDate()} ${"%02d:%02d".format(l.hour, l.minute)}")
                }
                val unpriced = rows.count { it.inTok + it.outTok > 0 && it.costUsd == null }
                sb.appendLine("显式带价格的记录：${rows.size - unpriced}；未计价：$unpriced")
                val sources = rows.groupingBy { it.source.ifEmpty { "gateway" } }.eachCount()
                sb.appendLine("记录来源分布：${sources.entries.joinToString { "${it.key}=${it.value}" }}")
                val providers = rows.map { it.provider }.distinct()
                sb.appendLine("供应商数：${providers.size}（含本地会话分组）")
                sb.appendLine()
                sb.appendLine("—— 云端（只读盘点）——")
                try {
                    val remote = magpie.repository().inventory(_state.value.usageDir)
                        .filter { UsageFileName.parse(it.name) != null }
                    sb.appendLine("云端用量文件数：${remote.size}")
                    val remoteDays = remote.mapNotNull { UsageFileName.parse(it.name)?.day }.sorted()
                    if (remoteDays.isNotEmpty()) {
                        sb.appendLine("云端日期覆盖：${remoteDays.first()} 至 ${remoteDays.last()}")
                    }
                    val perComputer = remote.groupingBy { UsageFileName.parse(it.name)!!.computerId }.eachCount()
                    sb.appendLine("云端设备分布：${perComputer.entries.joinToString { "${it.key.take(8)}…=${it.value}" }}")
                } catch (e: Exception) {
                    sb.appendLine("云端盘点失败：${e.message}")
                }
                sb.appendLine()
                sb.appendLine("说明：桌面端用量自动上传约 15 分钟一次（usageEvery），")
                sb.appendLine("云端保留最近 90 天（SharedDays）；手机前台检查间隔与它们无关。")
                _state.value = _state.value.copy(diagnostics = sb.toString())
            } catch (e: Exception) {
                _state.value = _state.value.copy(diagnostics = sb.appendLine("验证失败：${e.message}").toString())
            } finally {
                _state.value = _state.value.copy(diagnosticsRunning = false)
            }
        }
    }

    /** 显式清理本机归档（远端不受影响）。 */
    fun clearLocalArchive(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            magpie.store().clearAll()
            onDone("已清除本机归档（凭据与设置保留）")
        }
    }

    /** 从 models.dev 拉取最新价格表（裁剪后存本地）。 */
    fun updatePriceTable() {
        if (_state.value.priceUpdating) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(priceUpdating = true, priceMessage = null)
            try {
                val source = magpie.updatePriceTable()
                _state.value = _state.value.copy(
                    priceSource = source,
                    priceUpdating = false,
                    priceMessage = "已更新：models.dev ${source.fetchedAt}，重新计费完成",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    priceUpdating = false,
                    priceMessage = "更新失败：${e.message}",
                )
            }
        }
    }
}
