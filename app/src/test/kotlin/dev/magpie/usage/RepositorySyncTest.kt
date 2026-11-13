package dev.magpie.usage

import dev.magpie.usage.core.webdav.DavEntry
import dev.magpie.usage.core.webdav.DavError
import dev.magpie.usage.core.webdav.WebDavClient
import dev.magpie.usage.data.Credentials
import dev.magpie.usage.data.InMemoryUsageStore
import dev.magpie.usage.sync.SyncOutcome
import dev.magpie.usage.sync.UsageRepository
import dev.magpie.usage.testsupport.TestSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * 验收 1、2 的逻辑层验证：同一文件重复导入不增长；版本更新只替换本文件
 * 快照。存储用内存实现；Room 的 delete+insert 事务在设备上验证。
 */
class RepositorySyncTest {

    private class FakeDav(
        var files: Map<String, DavEntry>,
        val downloads: MutableMap<String, Int>,
        val bodies: Map<String, ByteArray>,
    ) : WebDavClient {
        override fun list(dirPath: String): List<DavEntry> = files.values.toList()
        override fun get(dirPath: String, fileName: String): ByteArray {
            downloads[fileName] = (downloads[fileName] ?: 0) + 1
            return bodies[fileName] ?: throw DavError.NotFound("404")
        }
    }

    private class FakeCreds(var map: Map<String, String>) : Credentials {
        override fun save(key: String, value: String) {
            map = map + (key to value)
        }

        override fun load(key: String): String? = map[key]
        override fun clear() {
            map = emptyMap()
        }
    }

    private val dir = "/magpie/usage/"

    private fun credentials(): FakeCreds = FakeCreds(
        mapOf(
            "account" to "user@example.com",
            "app_password" to "app-pass",
            "sync_passphrase" to Fixtures.passphrase,
        )
    )

    private fun repository(client: WebDavClient, creds: Credentials, store: InMemoryUsageStore): UsageRepository =
        UsageRepository({ _, _ -> client }, store, creds)

    @Test
    fun `a pinned connection keeps its own archive even when another is saved`() {
        // 同步开始时固定住连接：期间保存了另一个账号，这一轮仍写原来的库。
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = mapOf(f1.name to Base64.getDecoder().decode(f1.sealedBase64)),
        )
        val storeA = InMemoryUsageStore()
        val storeB = InMemoryUsageStore()
        val pinned = dev.magpie.usage.sync.FixedCredentials(
            "user@example.com", "app-pass", Fixtures.passphrase,
        )
        // A later save must not reach the pinned credentials.
        pinned.save("account", "someone-else")
        pinned.save("app_password", "other-password")
        assertEquals("user@example.com", pinned.load("account"))

        val repo = UsageRepository({ _, _ -> dav }, storeA, pinned)
        val outcome = repo.synchronize(dir)
        assertEquals(0, outcome.filesFailed)
        assertTrue("A 库应导入记录", storeA.allCalls().isNotEmpty())
        assertTrue("B 库不应被写入", storeB.allCalls().isEmpty())
    }

    @Test
    fun `a file whose server gives no version is re-read after the interval`() {
        // 没有 ETag、也没有 getlastmodified：版本恒为 "/"，若照常比对版本
        // 这个文件永远不会再下载。它应每隔 UNKNOWN_VERSION_REREAD_MS 重读一次。
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val downloads = HashMap<String, Int>()
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "/", 10, false, versionUnknown = true)),
            downloads = downloads,
            bodies = mapOf(f1.name to Base64.getDecoder().decode(f1.sealedBase64)),
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)
        repo.synchronize(dir)
        assertEquals(1, downloads[f1.name])
        // Right after the import it is fresh: no second download.
        repo.synchronize(dir)
        assertEquals(1, downloads[f1.name])

        // Age the import past the interval: the next pass reads it again.
        val known = store.file(f1.name)!!
        store.upsertFile(
            known.copy(importedAt = System.currentTimeMillis() - UsageRepository.UNKNOWN_VERSION_REREAD_MS - 1),
        )
        repo.synchronize(dir)
        assertEquals(2, downloads[f1.name])
    }

    @Test
    fun `an empty folder after a non-empty archive is not a clean sync`() {
        // 目录被改错/权限变了/404 被当空目录时，保留旧数据但必须如实报错，
        // 不能让状态行显示绿色。
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val store = InMemoryUsageStore()
        val body = Base64.getDecoder().decode(f1.sealedBase64)
        val withFiles = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = mapOf(f1.name to body),
        )
        repository(withFiles, credentials(), store).synchronize(dir)
        assertTrue(store.allCalls().isNotEmpty())

        val emptyFolder = FakeDav(files = emptyMap(), downloads = HashMap(), bodies = emptyMap())
        val outcome = repository(emptyFolder, credentials(), store).synchronize(dir)
        assertTrue("空目录不能算干净", outcome.hasFailures)
        assertTrue(outcome.failureMessage!!.contains("没有"))
        // The local archive is kept, as before.
        assertTrue(store.allCalls().isNotEmpty())
    }

    @Test
    fun `clearing the archive also clears the sync clock`() {
        val store = InMemoryUsageStore()
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = mapOf(f1.name to Base64.getDecoder().decode(f1.sealedBase64)),
        )
        repository(dav, credentials(), store).synchronize(dir)
        assertTrue(store.getMeta("last_checked_at") != null)
        store.clearAll()
        assertEquals(null, store.getMeta("last_checked_at"))
        assertEquals(null, store.getMeta("latest_record_at"))
    }

    @Test
    fun `acceptance 1 - refreshing the same version three times does not grow`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val f2 = Fixtures.byName("fedcba9876543210fedcba9876543210-2026-04-01.magpie-usage")
        val downloads = HashMap<String, Int>()
        val dav = FakeDav(
            files = mapOf(
                f1.name to DavEntry(f1.name, "\"v1\"", 10, false),
                f2.name to DavEntry(f2.name, "\"w1\"", 10, false),
            ),
            downloads = downloads,
            bodies = mapOf(
                f1.name to Base64.getDecoder().decode(f1.sealedBase64),
                f2.name to Base64.getDecoder().decode(f2.sealedBase64),
            ),
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)

        repeat(3) { repo.synchronize(dir) }
        assertEquals(2, downloads.size)
        assertEquals(1, downloads[f1.name])
        assertEquals(1, downloads[f2.name])
        assertEquals(9 + 1, store.allCalls().size)
    }

    @Test
    fun `acceptance 2 - an updated file replaces only its own snapshot`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val f2 = Fixtures.byName("fedcba9876543210fedcba9876543210-2026-04-01.magpie-usage")
        val downloads = HashMap<String, Int>()
        val bodies = mapOf(
            f1.name to Base64.getDecoder().decode(f1.sealedBase64),
            f2.name to Base64.getDecoder().decode(f2.sealedBase64),
        )
        val dav = FakeDav(
            files = mapOf(
                f1.name to DavEntry(f1.name, "\"v1\"", 10, false),
                f2.name to DavEntry(f2.name, "\"w1\"", 10, false),
            ),
            downloads = downloads,
            bodies = bodies,
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)
        repo.synchronize(dir)
        assertEquals(10, store.allCalls().size)

        // Day 2026-03-31 shrinks to 2 rows on the server: its version bumps.
        val shrunk = """
            {"computer":"0123456789abcdef0123456789abcdef","name":"Demo-PC","day":"2026-03-31","calls":[
            {"t":"2026-03-31T02:00:00Z","provider":"openai","in":1000,"out":200,"cache_read":3000,"cache_write":0,"status":200},
            {"t":"2026-03-31T02:05:00Z","provider":"openai","in":10,"out":2,"status":200}]}
        """.trimIndent()
        val reseal = Base64.getEncoder().encodeToString(
            TestSealer.seal(TestSealer.gzip(shrunk.toByteArray()), Fixtures.passphrase, ByteArray(16) { it.toByte() }, ByteArray(12) { (it + 40).toByte() })
        )
        val dav2 = FakeDav(
            files = mapOf(
                f1.name to DavEntry(f1.name, "\"v2\"", 10, false),
                f2.name to DavEntry(f2.name, "\"w1\"", 10, false),
            ),
            downloads = downloads,
            bodies = mapOf(
                f1.name to Base64.getDecoder().decode(reseal),
                f2.name to Base64.getDecoder().decode(f2.sealedBase64),
            ),
        )
        val repo2 = repository(dav2, credentials(), store)
        val outcome = repo2.synchronize(dir)

        // Only the changed file was downloaded again (f1: pass 1 + pass 2).
        assertEquals(1, outcome.filesChanged)
        assertEquals(2, downloads[f1.name])
        assertEquals(1, downloads[f2.name])
        // Old rows are gone, no duplicates, the untouched day survives.
        val f1State = store.file(f1.name)!!
        assertEquals("import should succeed: ${f1State.failedReason}", 1, outcome.filesImported)
        val rows = store.allCalls()
        assertEquals(2 + 1, rows.size)
        assertEquals(0, rows.count { it.day == "2026-03-31" && it.inTok != 1000L && it.inTok != 10L })
        assertEquals(1, rows.count { it.day == "2026-04-01" })
    }

    @Test
    fun `failed file keeps its manifest and the pass records it honestly`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val downloads = HashMap<String, Int>()
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = downloads,
            bodies = emptyMap(), // GET 404s: the file vanished between list and get
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)
        val outcome = repo.synchronize(dir)
        // A per-file failure is recorded and skipped; the pass still completes.
        assertEquals(1, outcome.filesFailed)
        assertEquals(0, store.allCalls().size)
        val known = store.file(f1.name)
        assertEquals(0L, known!!.importedAt)
        assertTrue(known.failedReason.isNotEmpty())
        // A failed file retries on the next pass even at the same version.
        val outcome2 = repo.synchronize(dir)
        assertEquals(1, outcome2.filesChanged)
    }

    @Test
    fun `a pass with a failed file is never reported as a clean sync`() {
        // 状态行曾无条件清掉错误：一个一直失败的文件会让界面显示同步正常。
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = emptyMap(),
        )
        val outcome = repository(dav, credentials(), InMemoryUsageStore()).synchronize(dir)
        assertEquals(1, outcome.filesFailed)
        assertTrue("失败的一轮不能算干净", outcome.hasFailures)
        assertTrue("状态行必须给出原因", !outcome.failureMessage.isNullOrEmpty())
        // 全部成功的轮次才是干净的。
        val ok = SyncOutcome(
            checked = true, filesChanged = 1, filesDownloaded = 1, filesImported = 1,
            filesFailed = 0, totalRemoteFiles = 1, remoteModifiedAt = null,
            latestRecordAt = null, error = null,
        )
        assertFalse(ok.hasFailures)
        assertNull(ok.failureMessage)
    }

    @Test
    fun `wrong passphrase aborts the whole pass`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val creds = credentials()
        creds.save("sync_passphrase", "не тот пароль")
        val dav = FakeDav(
            files = mapOf(f1.name to DavEntry(f1.name, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = mapOf(f1.name to Base64.getDecoder().decode(f1.sealedBase64)),
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, creds, store)
        assertThrows(dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder.DecodeError.BadPassphrase::class.java) {
            repo.synchronize(dir)
        }
        assertEquals(0, store.allCalls().size)
    }

    @Test
    fun `name-content mismatch is recorded and skipped`() {
        val f2 = Fixtures.byName("fedcba9876543210fedcba9876543210-2026-04-01.magpie-usage")
        val mismatchName = "0123456789abcdef-2026-04-01.magpie-usage"
        val dav = FakeDav(
            files = mapOf(mismatchName to DavEntry(mismatchName, "\"v1\"", 10, false)),
            downloads = HashMap(),
            bodies = mapOf(mismatchName to Base64.getDecoder().decode(f2.sealedBase64)),
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)
        val outcome = repo.synchronize(dir)
        assertEquals(1, outcome.filesFailed)
        assertEquals(0, store.allCalls().size)
        assertTrue(store.file(mismatchName)!!.failedReason.isNotEmpty())
    }

    @Test
    fun `non-usage files in the folder are ignored`() {
        val f1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")
        val dav = FakeDav(
            files = mapOf(
                f1.name to DavEntry(f1.name, "\"v1\"", 10, false),
                "note.txt" to DavEntry("note.txt", "\"v\"", 1, false),
                "0123456789abcdef-2026-13-40.magpie-usage" to DavEntry("0123456789abcdef-2026-13-40.magpie-usage", "\"v\"", 1, false),
            ),
            downloads = HashMap(),
            bodies = mapOf(f1.name to Base64.getDecoder().decode(f1.sealedBase64)),
        )
        val store = InMemoryUsageStore()
        val repo = repository(dav, credentials(), store)
        val outcome = repo.synchronize(dir)
        assertEquals(1, outcome.totalRemoteFiles)
    }
}
