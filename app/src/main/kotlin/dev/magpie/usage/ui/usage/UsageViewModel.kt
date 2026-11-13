package dev.magpie.usage.ui.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.magpie.usage.MagpieApp
import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.data.PriceSource
import dev.magpie.usage.data.SyncSettings
import dev.magpie.usage.data.applyOrganize
import dev.magpie.usage.domain.Providers
import dev.magpie.usage.domain.UsageAggregator
import dev.magpie.usage.domain.UsageHeatmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Holds the page's state: one data pass feeds cards, chart and list, so the
 * range switch never shows inconsistent figures.
 */
class UsageViewModel(app: Application) : AndroidViewModel(app) {

    private val magpie = app as MagpieApp

    enum class SyncState { IDLE, CHECKING, SYNCING, ERROR }

    data class UiState(
        val settings: SyncSettings? = null,
        val loading: Boolean = true,
        val syncState: SyncState = SyncState.IDLE,
        val syncMessage: String? = null,
        val lastCheckedAt: Long? = null,
        val lastImportAt: Long? = null,
        val latestRecordAt: Long? = null,
        val range: UsageAggregator.Range = UsageAggregator.Range.TODAY,
        val dimension: UsageAggregator.Dimension = UsageAggregator.Dimension.MODEL,
        val result: UsageAggregator.Result? = null,
        val heatmap: UsageHeatmap.Heatmap? = null,
        val priceSource: PriceSource = PriceSource.EMPTY,
        /** 概览：供应商用量（总量 + 可展开的账号拆分），已按整理排序/过滤。 */
        val overviewShares: List<UsageAggregator.ProviderUsageShare> = emptyList(),
        /** 概览+整理：全部供应商（未剔除隐藏），整理页用它排所有项。 */
        val overviewSharesAll: List<UsageAggregator.ProviderUsageShare> = emptyList(),
        val organize: dev.magpie.usage.data.OrganizeState = dev.magpie.usage.data.OrganizeState(),
        /** 概览额度：桌面共享的额度历史（托盘样式展示），已按整理排序/过滤。 */
        val quotaGroups: List<dev.magpie.usage.domain.QuotaOverviewBuilder.GroupView> = emptyList(),
        val providerIds: List<String> = emptyList(),
        val selectedProviders: Set<String>? = null, // null = all
        val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
        val firstImportProgress: Pair<Int, Int>? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    // Collectors launched by init can reload immediately on an IO thread.
    private val reloadSeq = java.util.concurrent.atomic.AtomicLong(0)

    init {
        // 配置是持续观察的：在设置页保存后返回用量页，这里立即感知
        // （第一版只在启动时读一次，导致"已保存但用量页仍显示未连接"）。
        viewModelScope.launch(Dispatchers.IO) {
            magpie.settingsStore.settings.collect { s ->
                val zone = if (s.timeZoneMode == "system") ZoneId.systemDefault() else ZoneId.of("Asia/Shanghai")
                // 显示偏好是全局的：写进 Formatters，所有页面立即生效。
                dev.magpie.usage.domain.Formatters.numberUnit = s.numberUnit
                dev.magpie.usage.domain.Formatters.currency = s.currency
                dev.magpie.usage.domain.Formatters.exchangeRate = s.exchangeRate
                val wasConfigured = _state.value.settings?.configured == true
                _state.value = _state.value.copy(settings = s, zone = zone, loading = false)
                if (s.configured && !wasConfigured) {
                    // 刚完成配置（或应用启动时已配置）：立即同步并开始前台轮询。
                    refresh(initial = false)
                    magpie.sync().startForegroundPolling(s.foregroundMinutes)
                }
                reload()
            }
        }
        // 价格表加载/更新后重算费用。
        viewModelScope.launch(Dispatchers.IO) {
            magpie.priceSource().collect { source ->
                _state.value = _state.value.copy(priceSource = source)
                reload()
            }
        }
        // 整理（顺序/隐藏）变化后重排概览。
        viewModelScope.launch(Dispatchers.IO) {
            magpie.organizeStore.state.collect { o ->
                _state.value = _state.value.copy(organize = o)
                reload()
            }
        }
        // 每一轮同步（前台轮询、手动刷新、后台任务）结束后都刷新页面：
        // 原先只有手动刷新会 reload，停在页面上时云端新数据不会显示，
        // 自动刷新的失败也不会变成红色提示。
        viewModelScope.launch(Dispatchers.IO) {
            var seen = magpie.syncGeneration.value
            magpie.syncGeneration.collect { gen ->
                if (gen == seen) return@collect
                seen = gen
                val report = magpie.lastReport.value
                if (report != null && report.connectionKey == magpie.currentConnectionKey()) {
                    val failure = report.outcome.failureMessage
                    _state.value = _state.value.copy(
                        syncState = if (failure != null) SyncState.ERROR else SyncState.IDLE,
                        syncMessage = failure,
                    )
                }
                reload()
            }
        }
    }

    fun setTheme(mode: String) {
        viewModelScope.launch { magpie.settingsStore.saveTheme(mode) }
    }

    fun setRange(range: UsageAggregator.Range) {
        _state.value = _state.value.copy(range = range)
        reload()
    }

    fun setDimension(dimension: UsageAggregator.Dimension) {
        _state.value = _state.value.copy(dimension = dimension)
        reload()
    }

    fun setSelectedProviders(selected: Set<String>?) {
        _state.value = _state.value.copy(selectedProviders = selected)
        reload()
    }

    /** 整理：上移/下移一项（与相邻项交换位置），落盘完整顺序。 */
    fun moveProvider(provider: String, delta: Int) {
        val all = organizeProviders()
        val saved = _state.value.organize.order.filter { p -> all.contains(p) }
        val current = saved + all.filter { it !in saved.toSet() }
        val from = current.indexOf(provider)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return
        val next = current.toMutableList()
        val item = next.removeAt(from)
        next.add(to, item)
        viewModelScope.launch(Dispatchers.IO) {
            magpie.organizeStore.save(next, _state.value.organize.hidden, _state.value.organize.hiddenAccounts)
        }
    }

    /** 整理：显示/隐藏某个供应商（只影响概览）。 */
    fun setHidden(provider: String, hidden: Boolean) {
        val current = _state.value.organize.hidden.toMutableSet()
        if (hidden) current.add(provider) else current.remove(provider)
        val all = organizeProviders()
        val saved = _state.value.organize.order.filter { all.contains(it) }
        val order = saved + all.filter { it !in saved.toSet() }
        viewModelScope.launch(Dispatchers.IO) {
            magpie.organizeStore.save(order, current, _state.value.organize.hiddenAccounts)
        }
    }

    /** 整理：账号是否在供应商下列出（不列出只是不显示，数据仍计入）。 */
    fun setAccountHidden(provider: String, account: String, hidden: Boolean) {
        val key = "$provider|$account"
        val current = _state.value.organize.hiddenAccounts.toMutableSet()
        if (hidden) current.add(key) else current.remove(key)
        val all = organizeProviders()
        val saved = _state.value.organize.order.filter { all.contains(it) }
        val order = saved + all.filter { it !in saved.toSet() }
        viewModelScope.launch(Dispatchers.IO) {
            magpie.organizeStore.save(order, _state.value.organize.hidden, current)
        }
    }

    /** 整理：把当前供应商重置为按用量降序（清空自定义顺序，保留隐藏）。 */
    fun resetProviderOrder() {
        viewModelScope.launch(Dispatchers.IO) {
            magpie.organizeStore.save(
                emptyList(), _state.value.organize.hidden, _state.value.organize.hiddenAccounts,
            )
        }
    }

    /** 整理页的供应商全集：额度里有 + 用量里有，去重。 */
    private fun organizeProviders(): List<String> {
        val quota = _state.value.quotaGroups.map { it.provider }
        val usage = _state.value.overviewSharesAll.map { it.provider }
        return (quota + usage.filter { it !in quota.toSet() }).distinct()
    }

    /**
     * Numbers reloaded by one request. Every reload takes the next number
     * and only commits if it is still the latest: a slow pass over the old
     * archive (or an old range) must never overwrite the page after a newer
     * one has landed.
     */
    /** All providers ever imported, for the filter sheet. */
    fun reload() {
        val seq = reloadSeq.incrementAndGet()
        // Everything this pass must NOT re-read halfway through: the range,
        // zone, dimension and filters the user chose when it started.
        val snap = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            // One store for the whole pass: the key and the archive come
            // from the same connection snapshot.
            val archive = magpie.activeArchive()
            val store = archive.store
            val rows = store.allCalls()
            val ids = rows.map { it.provider }.distinct().sortedWith(
                compareByDescending<String> { p -> rows.filter { it.provider == p }.sumOf { it.allTokens } }
            )
            val effectiveFilter = snap.selectedProviders?.intersect(ids.toSet())?.ifEmpty { null }
            val lookup = snap.priceSource.lookup
            val priceOf = lookup?.let { l -> { r: CallRow -> l.catalogCostOf(r) } }
            val result = UsageAggregator.summarize(
                rows = rows,
                range = snap.range,
                zone = snap.zone,
                providerFilter = effectiveFilter,
                dimension = snap.dimension,
                priceOf = priceOf,
            )
            val heatmap = UsageHeatmap.build(
                rows = rows,
                zone = snap.zone,
                weeks = UsageHeatmap.DEFAULT_WEEKS,
                providerFilter = effectiveFilter,
                priceOf = priceOf,
            )
            // 概览的数据：供应商用量（总量+账号拆分），按整理排序/过滤。
            val sharesAll = UsageAggregator.byProviderAccount(
                rows = rows, range = snap.range, zone = snap.zone,
                providerFilter = effectiveFilter, priceOf = priceOf,
            )
            val shares = sharesAll.applyOrganize(snap.organize)
            // 额度历史：托盘样式，同样按整理排序/隐藏。
            val quotaAll = dev.magpie.usage.domain.QuotaOverviewBuilder.build(store.quotaLines()).groups
            val o = snap.organize
            val byId = quotaAll.associateBy { it.provider }
            val quotaOrdered = (o.order.mapNotNull { byId[it] } +
                quotaAll.filter { it.provider !in o.order.toSet() })
                .filter { it.provider !in o.hidden }

            // Superseded by a newer reload, or the connection changed under
            // us: this pass's figures belong to a page that is gone.
            if (reloadSeq.get() != seq) return@launch
            if (magpie.activeArchive().key != archive.key) return@launch

            _state.value = _state.value.copy(
                result = result,
                heatmap = heatmap,
                providerIds = ids,
                selectedProviders = effectiveFilter,
                overviewShares = shares,
                overviewSharesAll = sharesAll,
                quotaGroups = quotaOrdered,
                lastCheckedAt = store.getMeta("last_checked_at")?.toLongOrNull(),
                lastImportAt = store.getMeta("last_successful_import_at")?.toLongOrNull(),
                latestRecordAt = store.getMeta("latest_record_at")?.toLongOrNull(),
            )
        }
    }

    fun refresh(initial: Boolean = false) {
        // The connection this attempt belongs to. A pass that finishes after
        // the user switched connections says nothing about the page now, so
        // its error must not repaint a different account's status line.
        val connection = magpie.currentConnectionKey()
        viewModelScope.launch(Dispatchers.IO) {
            if (magpie.currentConnectionKey() != connection) return@launch
            _state.value = _state.value.copy(syncState = SyncState.CHECKING, syncMessage = null)
            try {
                magpie.sync().refreshNow(force = !initial)
                if (magpie.currentConnectionKey() != connection) return@launch
                // A pass that imported what it could but skipped a torn file is
                // not a clean sync — the status line keeps saying so instead of
                // turning green over a file that keeps failing.
                val report = magpie.lastReport.value
                val failure = report?.takeIf { it.connectionKey == connection }
                    ?.outcome?.failureMessage
                magpie.noteSyncError(failure)
                _state.value = _state.value.copy(
                    syncState = if (failure != null) SyncState.ERROR else SyncState.IDLE,
                    syncMessage = failure,
                )
            } catch (e: Exception) {
                if (magpie.currentConnectionKey() != connection) return@launch
                magpie.noteSyncError(e.message ?: "同步失败")
                _state.value = _state.value.copy(
                    syncState = SyncState.ERROR,
                    syncMessage = e.message ?: "同步失败",
                )
            }
            reload()
        }
    }

    fun onResumed() {
        val settings = _state.value.settings
        if (settings?.configured == true) {
            magpie.sync().startForegroundPolling(settings.foregroundMinutes)
            refresh(initial = true)
        } else {
            reload()
        }
    }

    fun onPaused() {
        magpie.sync().stopForegroundPolling()
    }

    override fun onCleared() {
        magpie.sync().stopForegroundPolling()
        super.onCleared()
    }
}
