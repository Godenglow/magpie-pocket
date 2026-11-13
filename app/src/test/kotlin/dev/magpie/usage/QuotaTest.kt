package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.pack.QuotaPackParser
import dev.magpie.usage.data.InMemoryUsageStore
import dev.magpie.usage.data.db.QuotaPointEntity
import dev.magpie.usage.domain.QuotaOverviewBuilder
import dev.magpie.usage.domain.UsageAggregator
import dev.magpie.usage.sync.QUOTAS_EXT
import dev.magpie.usage.sync.UsageRepository
import dev.magpie.usage.core.webdav.DavEntry
import dev.magpie.usage.core.webdav.WebDavClient
import dev.magpie.usage.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 额度历史（.magpie-quotas）的解析、合并与概览构建。 */
class QuotaTest {

    private val sample = """
        {"workbuddy|goldenglow":{
          "5 小时":[
            {"at":"2026-11-12T10:00:00Z","left":80.0},
            {"at":"2026-11-12T12:00:00Z","left":55.5,"start":"2026-11-12T09:00:00Z","resetsAt":"2026-11-12T14:00:00Z"}
          ],
          "7 天":[{"at":"2026-11-12T12:00:00Z","left":90.0,"resetsAt":"2026-11-19T00:00:00Z"}]
        },
        "deepseek|":{"balance-only":true}}
    """.trimIndent()

    @Test
    fun `parses accounts windows and points`() {
        val points = QuotaPackParser.parse(sample.toByteArray())
        assertEquals(3, points.size)
        val fiveHour = points.filter { it.windowName == "5 小时" }.sortedBy { it.at }
        assertEquals("workbuddy", fiveHour[0].provider)
        assertEquals("goldenglow", fiveHour[0].user)
        assertEquals(80.0, fiveHour[0].left, 1e-9)
        assertEquals(55.5, fiveHour[1].left, 1e-9)
        assertEquals(Instant.parse("2026-11-12T14:00:00Z").toEpochMilli(), fiveHour[1].resetsAt)
        // 非对象结构（deepseek| 的 balance-only）被跳过，不抛错。
    }

    @Test
    fun `overview picks each window's latest reading`() {
        val points = QuotaPackParser.parse(sample.toByteArray())
        val lines = listOf(
            dev.magpie.usage.data.QuotaLineData(
                provider = points[0].provider, user = points[0].user,
                windowName = "5 小时",
                points = points.filter { it.windowName == "5 小时" }
                    .map { Triple(it.at, it.left, it.startAt to it.resetsAt) },
            ),
            dev.magpie.usage.data.QuotaLineData(
                provider = points[0].provider, user = points[0].user,
                windowName = "7 天",
                points = points.filter { it.windowName == "7 天" }
                    .map { Triple(it.at, it.left, it.startAt to it.resetsAt) },
            ),
        )
        val overview = QuotaOverviewBuilder.build(lines)
        assertEquals(1, overview.groups.size)
        val account = overview.groups[0].accounts.single()
        assertEquals(2, account.windows.size)
        val fiveHour = account.windows.first { it.name == "5 小时" }
        assertEquals(55.5, fiveHour.leftPercent, 1e-9)
        assertEquals(2, fiveHour.trend.size)
        assertEquals(Instant.parse("2026-11-12T14:00:00Z").toEpochMilli(), fiveHour.resetsAt)
        // 单账号也给总览网格（手机上一律先显示总览，点账号数才展开细节），
        // 窗口数与账号自己的窗口数相同。
        assertEquals(2, overview.groups[0].merged.size)
    }

    @Test
    fun `merged strip averages windows shared by two accounts`() {
        val lines = listOf(
            dev.magpie.usage.data.QuotaLineData("p", "a", "5 小时", listOf(Triple(1L, 80.0, null to null))),
            dev.magpie.usage.data.QuotaLineData("p", "b", "5 小时", listOf(Triple(1L, 60.0, null to null))),
            dev.magpie.usage.data.QuotaLineData("p", "a", "7 天", listOf(Triple(1L, 50.0, null to null))),
        )
        val group = QuotaOverviewBuilder.build(lines).groups.single()
        val merged = group.merged.single() // 只有两账号都有的 5 小时
        assertEquals("5 小时", merged.name)
        assertEquals(70.0, merged.leftPercent, 1e-9)
        assertEquals(2, merged.accounts)
    }

    @Test
    fun `a reading whose window reset or that nobody refreshed is marked stale`() {
        // 桌面只共享历史读数：窗口已重置、或这台机器一天没上报时，那个
        // "剩余 75%" 不是现在的余额，必须标出来（否则概览把历史当现值）。
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        fun line(user: String, window: String, at: String, resets: String?) =
            dev.magpie.usage.data.QuotaLineData(
                "p", user, window,
                listOf(Triple(Instant.parse(at).toEpochMilli(), 75.0, null to resets?.let { Instant.parse(it).toEpochMilli() })),
            )
        val lines = listOf(
            // 一小时内、窗口还没重置：现值。
            line("fresh", "5 小时", "2026-11-13T11:30:00Z", "2026-11-13T14:00:00Z"),
            // 窗口已经重置过：旧读数是历史。
            line("reset", "5 小时", "2026-11-13T11:30:00Z", "2026-11-13T11:45:00Z"),
            // 一天多没有新读数：这台机器早就不报了。
            line("old", "7 天", "2026-11-11T10:00:00Z", "2026-11-20T00:00:00Z"),
        )
        val groups = QuotaOverviewBuilder.build(lines, now).groups
        val windows = groups.single().accounts.associate { a ->
            a.user to a.windows.single()
        }
        assertEquals(false, windows.getValue("fresh").stale)
        assertEquals(true, windows.getValue("reset").stale)
        assertEquals(true, windows.getValue("old").stale)
    }

    @Test
    fun `merged percentage is stale when any included account has an expired reading`() {
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        val freshAt = now - 60_000
        val oldAt = now - QuotaOverviewBuilder.STALE_AFTER_MS - 1
        val lines = listOf(
            dev.magpie.usage.data.QuotaLineData("p", "fresh", "Credits", listOf(Triple(freshAt, 80.0, null to null))),
            dev.magpie.usage.data.QuotaLineData("p", "old", "Credits", listOf(Triple(oldAt, 20.0, null to null))),
        )

        val merged = QuotaOverviewBuilder.build(lines, now).groups.single().merged.single()
        assertEquals(50.0, merged.leftPercent, 1e-9)
        assertTrue(merged.stale)
        assertEquals(1, merged.staleAccounts)
        assertEquals(oldAt, merged.at)
    }

    @Test
    fun `repository imports and merges quota files across computers`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val quotaJson = """{"zcode|me":{"5 小时":[{"at":"2026-11-12T10:00:00Z","left":70.0}]}}"""
        val quotaName1 = "0123456789abcdef0123456789abcdef$QUOTAS_EXT"
        val quotaName2 = "fedcba9876543210fedcba9876543210$QUOTAS_EXT"
        val quotaSealed = dev.magpie.usage.testsupport.TestSealer.seal(
            dev.magpie.usage.testsupport.TestSealer.gzip(quotaJson.toByteArray()),
            Fixtures.passphrase,
            ByteArray(16) { (it + 60).toByte() }, ByteArray(12) { (it + 70).toByte() },
        )
        val quotaSealed2 = dev.magpie.usage.testsupport.TestSealer.seal(
            dev.magpie.usage.testsupport.TestSealer.gzip(
                """{"zcode|me":{"5 小时":[{"at":"2026-11-12T11:00:00Z","left":60.0}]}}""".toByteArray(),
            ),
            Fixtures.passphrase, ByteArray(16) { (it + 80).toByte() }, ByteArray(12) { (it + 90).toByte() },
        )
        val downloads = HashMap<String, Int>()
        val dav = object : WebDavClient {
            override fun list(dirPath: String) = listOf(
                DavEntry(f1.name, "\"v1\"", 1, false),
                DavEntry(quotaName1, "\"q1\"", 1, false),
                DavEntry(quotaName2, "\"q2\"", 1, false),
            )

            override fun get(dirPath: String, fileName: String): ByteArray {
                downloads[fileName] = (downloads[fileName] ?: 0) + 1
                return when (fileName) {
                    f1.name -> java.util.Base64.getDecoder().decode(f1.sealedBase64)
                    quotaName1 -> quotaSealed
                    quotaName2 -> quotaSealed2
                    else -> throw dev.magpie.usage.core.webdav.DavError.NotFound("404")
                }
            }
        }
        val creds = object : Credentials {
            val map = mutableMapOf(
                "account" to "u", "app_password" to "p", "sync_passphrase" to Fixtures.passphrase,
            )

            override fun save(key: String, value: String) {
                map[key] = value
            }

            override fun load(key: String): String? = map[key]
            override fun clear() {
                map.clear()
            }
        }
        val store = InMemoryUsageStore()
        val repo = UsageRepository({ _, _ -> dav }, store, creds)
        val run1 = repo.synchronize("/magpie/usage/")
        assertEquals("首轮导入失败：${run1.error}", 0, run1.filesFailed)
        val run2 = repo.synchronize("/magpie/usage/") // 第二轮：版本未变，不再下载
        assertEquals("次轮导入失败：${run2.error}", 0, run2.filesFailed)
        assertEquals(1, downloads[quotaName1])
        assertEquals(1, downloads[quotaName2])

        val lines = store.quotaLines()
        assertEquals(1, lines.size)
        val line = lines.first()
        assertEquals("zcode", line.provider)
        assertEquals("me", line.user)
        assertEquals(2, line.points.size) // 两台电脑的点合并，按时间排序
        assertEquals(70.0, line.points[0].second, 1e-9)
        assertEquals(60.0, line.points[1].second, 1e-9)
    }
}

/** 账号维度：providerAccount 优先，其次 Host 的 " as 账号"。 */
class AccountDimensionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun row(host: String, providerAccount: String, inTok: Long): CallRow = CallRow(
        fileName = "f", ordInFile = 0, computer = "c", day = "d",
        t = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli(),
        agent = "a", provider = "workbuddy", model = "m", requested = "", served = "",
        inTok = inTok, outTok = 0, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
        cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
        status = 200, err = "", errType = "", rid = "", session = "", sessionProvider = "",
        host = host, providerAccount = providerAccount,
        source = "", tries = 0, handedOn = false, rejected = false, costUsd = null, credits = null,
        upBytes = 0, downBytes = 0, raw = "",
    )

    @Test
    fun `account prefers providerAccount then host-as`() {
        assertEquals("Goldenglow", CallRow.accountOf("h as other", "Goldenglow"))
        assertEquals("拉布拉路", CallRow.accountOf("api.x.com as 拉布拉路", ""))
        assertEquals("", CallRow.accountOf("api.x.com", ""))
    }

    @Test
    fun `account dimension groups records`() {
        val rows = listOf(
            row("", "Goldenglow", 100),
            row("api.x.com as 拉布拉路", "", 50),
            row("", "", 10),
        )
        val r = UsageAggregator.summarize(
            rows, UsageAggregator.Range.ALL, zone,
            Instant.parse("2026-11-13T02:00:00Z"),
            dimension = UsageAggregator.Dimension.ACCOUNT,
        )
        val ids = r.providers.map { it.id }
        assertEquals(setOf("Goldenglow", "拉布拉路", ""), ids.toSet())
        assertEquals(100L, r.providers.first { it.id == "Goldenglow" }.totals.allTokens)
        assertEquals("未知账号", UsageAggregator.Dimension.ACCOUNT.display(""))
    }

    @Test
    fun `today summary equals the same rules as usage`() {
        val rows = listOf(row("", "A", 100), row("", "A", 40).copy(rejected = true, status = 400))
        val r = UsageAggregator.summarize(
            rows, UsageAggregator.Range.TODAY, zone,
            Instant.parse("2026-11-13T02:00:00Z"),
        )
        // 拒绝行不入汇总。
        assertEquals(1L, r.totals.calls)
        assertEquals(100L, r.totals.allTokens)
        assertEquals(LocalDate.of(2026, 11, 13), r.since!!.atZone(zone).toLocalDate())
    }
}
