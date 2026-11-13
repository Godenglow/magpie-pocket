package dev.magpie.usage.domain

import dev.magpie.usage.data.QuotaLineData

/**
 * 概览的额度数据：桌面共享的额度历史（.magpie-quotas）里，每个
 * 供应商·账号·窗口的最新读数与走势。桌面概览卡片里的绝对数值
 * （余额、积分总数）是桌面实时读数，不在同步文件里——手机显示
 * 剩余百分比、重置时间与走势，诚实呈现。
 */
object QuotaOverviewBuilder {

    /**
     * A reading older than this is no longer a balance: the desktop reports
     * about every 15 minutes while it runs, so a day-old one means the
     * machine (or the account) stopped reporting and the percentage is
     * history, not what is left now.
     */
    const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    data class WindowView(
        val name: String,
        /** Latest reading's left percent (0..1 comes as 0..100 from desktop). */
        val leftPercent: Double,
        /** When that reading was taken (epoch ms). */
        val at: Long,
        val resetsAt: Long?,
        /** (at, leftPercent) oldest first, for the trend curve. */
        val trend: List<Pair<Long, Double>>,
        /**
         * True when this reading no longer says what is left now: the window
         * it belongs to has already reset, or the account stopped reporting
         * ([STALE_AFTER_MS] without a newer reading). The panel marks these
         * rather than drawing history as the current balance.
         */
        val stale: Boolean = false,
    )

    data class AccountView(
        val provider: String,
        val user: String,
        val windows: List<WindowView>,
    )

    /** 托盘"全部 N 个账号"合并条：同名窗口跨账号的平均剩余。
     *  手机只有百分比读数（mergeQuotas 的 share 类），绝对量不在此文件里。 */
    data class MergedWindow(
        val name: String,
        val leftPercent: Double,
        val accounts: Int,
        val resetsAt: Long?,
        /** The newest reading behind this figure. */
        val at: Long = 0,
        /** At least one reading behind the average is stale. */
        val stale: Boolean = false,
        /** Number of accounts whose reading has expired. */
        val staleAccounts: Int = 0,
    )

    data class GroupView(
        val provider: String,
        val accounts: List<AccountView>,
        val merged: List<MergedWindow>,
    )

    data class Overview(val groups: List<GroupView>)

    fun build(lines: List<QuotaLineData>, now: Long = System.currentTimeMillis()): Overview {
        val byAccount = LinkedHashMap<Pair<String, String>, MutableList<QuotaLineData>>()
        for (line in lines) {
            if (line.points.isEmpty()) continue
            byAccount.getOrPut(line.provider to line.user) { mutableListOf() }.add(line)
        }
        val groups = byAccount.entries
            .groupBy({ it.key.first }, { it.key.second to it.value })
            .map { (provider, accountEntries) ->
                val accounts = accountEntries.map { (user, accountLines) ->
                    AccountView(
                        provider = provider,
                        user = user,
                        windows = accountLines.map { line ->
                            val sorted = line.points.sortedBy { it.first }
                            val latest = sorted.last()
                            val resetsAt = latest.third.second
                            WindowView(
                                name = line.windowName,
                                leftPercent = latest.second.coerceIn(0.0, 100.0),
                                at = latest.first,
                                resetsAt = resetsAt,
                                trend = sorted.map { it.first to it.second },
                                // A reading whose window has already reset, or
                                // one nobody has refreshed for a day, is history
                                // — not what is left now. Desktop AsOf marks a
                                // cached live response; history has no AsOf.
                                stale = (resetsAt != null && resetsAt <= now) ||
                                    now - latest.first > STALE_AFTER_MS,
                            )
                        }.sortedBy { it.name },
                    )
                }.sortedBy { it.user }
                // 总览条（mergeQuotas 的 share 分支）：同名窗口跨账号取平均。
                // 只有一个账号时，它自己的窗口就是总览（桌面的 "All 1 account"
                // 没有意义，但手机上一律先给总览、点账号数才展开细节）。
                val byWindow = LinkedHashMap<String, MutableList<WindowView>>()
                for (account in accounts) {
                    for (w in account.windows) byWindow.getOrPut(w.name) { mutableListOf() }.add(w)
                }
                val minAccounts = if (accounts.size >= 2) 2 else 1
                val merged = byWindow
                    .filter { it.value.size >= minAccounts }
                    .map { (name, ws) ->
                        MergedWindow(
                            name = name,
                            leftPercent = ws.sumOf { it.leftPercent } / ws.size,
                            accounts = ws.size,
                            resetsAt = ws.mapNotNull { it.resetsAt }.minOrNull(),
                            // The average includes every account. Its oldest
                            // reading determines how old that figure can be.
                            at = ws.minOf { it.at },
                            stale = ws.any { it.stale },
                            staleAccounts = ws.count { it.stale },
                        )
                    }
                    .sortedBy { it.name }
                GroupView(provider = provider, accounts = accounts, merged = merged)
            }
            .sortedBy { it.provider }
        return Overview(groups)
    }
}
