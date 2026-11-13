package dev.magpie.usage.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.organizeDataStore by preferencesDataStore(name = "magpie.organize")

/**
 * 整理（对应桌面概览的"整理"面板）：供应商在本面板的展示顺序与是否隐藏。
 * 隐藏只影响概览页的供应商用量卡片；用量页与统计不受影响（与桌面文案一致）。
 * 顺序用逗号分隔的 provider id 串存；不在串里的供应商按用量降序排在后面。
 */
data class OrganizeState(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** 不展示在供应商列表下的账号，键 "provider|user"。数据保留、只隐藏显示。 */
    val hiddenAccounts: Set<String> = emptySet(),
)

class OrganizeStore(private val context: Context) {

    private object Keys {
        val order = stringPreferencesKey("provider_order")
        val hidden = stringPreferencesKey("hidden_providers")
        val hiddenAccounts = stringPreferencesKey("hidden_accounts")
    }

    val state: Flow<OrganizeState> = context.organizeDataStore.data.map { p ->
        OrganizeState(
            order = (p[Keys.order] ?: "").split(',').filter { it.isNotEmpty() },
            hidden = (p[Keys.hidden] ?: "").split(',').filter { it.isNotEmpty() }.toSet(),
            hiddenAccounts = (p[Keys.hiddenAccounts] ?: "").split(',').filter { it.isNotEmpty() }.toSet(),
        )
    }

    suspend fun snapshot(): OrganizeState = state.first()

    /** 保存顺序、隐藏供应商与隐藏账号；空 id 过滤，串内去重。 */
    suspend fun save(order: List<String>, hidden: Set<String>, hiddenAccounts: Set<String>) {
        val cleanOrder = order.filter { it.isNotEmpty() }.distinct()
        val cleanHidden = hidden.filter { it.isNotEmpty() }.toSet()
        val cleanHiddenAccounts = hiddenAccounts.filter { it.isNotEmpty() }.toSet()
        context.organizeDataStore.edit { p ->
            p[Keys.order] = cleanOrder.joinToString(",")
            p[Keys.hidden] = cleanHidden.joinToString(",")
            p[Keys.hiddenAccounts] = cleanHiddenAccounts.joinToString(",")
        }
    }
}

/**
 * 把整理状态应用到供应商用量列表：先按 order 里出现的顺序排，
 * order 之外的（新出现的供应商）按传入顺序（用量降序）排在后面；
 * hidden 的整项剔除。
 */
fun List<dev.magpie.usage.domain.UsageAggregator.ProviderUsageShare>.applyOrganize(
    organize: OrganizeState,
): List<dev.magpie.usage.domain.UsageAggregator.ProviderUsageShare> {
    val byId = associateBy { it.provider }
    val ordered = organize.order.mapNotNull { byId[it] }
    val rest = filter { it.provider !in organize.order.toSet() }
    return (ordered + rest).filter { it.provider !in organize.hidden }
}
