package dev.magpie.usage.sync

import dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder
import dev.magpie.usage.core.model.RemoteFileInfo
import dev.magpie.usage.core.pack.Gzip
import dev.magpie.usage.core.pack.Limits
import dev.magpie.usage.core.pack.PackParser
import dev.magpie.usage.core.pack.QuotaPackParser
import dev.magpie.usage.core.pack.UsageFileName
import dev.magpie.usage.core.webdav.DavEntry
import dev.magpie.usage.core.webdav.DavError
import dev.magpie.usage.core.webdav.WebDavClient
import dev.magpie.usage.data.Credentials
import dev.magpie.usage.data.UsageStore

/**
 * Reads the cloud read-only and keeps the phone's archive true to it:
 *  - list the usage folder (all files, so a back-filled old day is caught);
 *  - skip files whose version is unchanged;
 *  - download, verify, decrypt, gunzip, parse, then replace that file's
 *    snapshot in one transaction;
 *  - never advance the manifest when a file fails.
 *
 * Reference: yetone/magpie @ 6acf5ee5, internal/davsync/usage.go.
 */
class UsageRepository(
    private val clientFactory: (account: String, appPassword: String) -> WebDavClient,
    private val store: UsageStore,
    private val credentials: Credentials,
) {

    class ImportFailure(message: String) : Exception(message)

    companion object {
        /**
         * How often a file whose server gives no version at all is read again.
         * Six hours bounds the work (each pass would otherwise download the
         * whole folder) while still noticing a changed day eventually.
         */
        const val UNKNOWN_VERSION_REREAD_MS = 6L * 60 * 60 * 1000
    }

    /**
     * One sync pass. [forceAll] re-reads every file even when versions match
     * (diagnostics only — not an ordinary refresh).
     */
    fun synchronize(dirPath: String, forceAll: Boolean = false): SyncOutcome {
        val appPassword = credentials.load(CredentialKeys.APP_PASSWORD)
            ?: throw ImportFailure("未设置应用密码")
        val account = credentials.load(CredentialKeys.ACCOUNT)
            ?: throw ImportFailure("未设置账号")
        val client = clientFactory(account, appPassword)

        val listed: Map<String, DavEntry> = client.list(dirPath).associateBy { it.name }
        val usageFiles = listed.filterKeys { it.endsWith(UsageFileName.EXTENSION) && UsageFileName.parse(it) != null }
        val quotaFiles = listed.filterKeys { it.endsWith(QUOTAS_EXT) }

        // A folder that has served files before and now lists none is not a
        // successful sync: it is a wrong path, a permission change or a 404
        // read as an empty folder. Keeping the old archive silently would
        // leave the page green over data that is no longer being updated.
        if (usageFiles.isEmpty() && quotaFiles.isEmpty() && store.files().isNotEmpty()) {
            store.setMeta(MetaKeys.LAST_CHECKED_AT, System.currentTimeMillis().toString())
            return SyncOutcome(
                checked = true,
                filesChanged = 0,
                filesDownloaded = 0,
                filesImported = 0,
                filesFailed = 0,
                totalRemoteFiles = 0,
                remoteModifiedAt = null,
                latestRecordAt = store.getMeta(MetaKeys.LATEST_RECORD_AT)?.toLongOrNull(),
                error = "云端目录里没有任何用量文件：检查用量目录路径、账号权限与桌面的共享用量开关",
            )
        }

        var downloaded = 0
        var imported = 0
        var failed = 0
        val failures = ArrayList<String>()
        var remoteModifiedAt: Long? = null
        usageFiles.values.maxOfOrNull { it.lastModifiedMs }?.let { if (it > 0) remoteModifiedAt = it }
        var latestRecordAt: Long? = store.getMeta(MetaKeys.LATEST_RECORD_AT)?.toLongOrNull()

        val toImport = ArrayList<Pair<String, DavEntry>>()
        val nowMs = System.currentTimeMillis()
        for ((name, entry) in usageFiles) {
            val known = store.file(name)
            // importedAt == 0 marks a file that failed earlier: retry it even
            // though the remote version is unchanged ("失败不推进版本号").
            // A parse-version bump re-reads every stored file once, so new
            // fields (session_provider in v2) fill without re-downloading
            // anything the remote has not touched.
            //
            // A server that gives neither ETag nor getlastmodified yields a
            // placeholder version that never changes: such a file is re-read
            // once every UNKNOWN_VERSION_REREAD_MS instead of never.
            val staleUnknownVersion = entry.versionUnknown &&
                (known == null || nowMs - known.importedAt > UNKNOWN_VERSION_REREAD_MS)
            if (forceAll || known == null || known.version != entry.version ||
                known.importedAt == 0L || known.parseVersion < PackParser.PARSE_VERSION ||
                staleUnknownVersion
            ) {
                toImport.add(name to entry)
            }
        }

        for ((name, entry) in toImport) {
            try {
                val sealed = client.get(dirPath, name)
                if (sealed.size > Limits.DOWNLOAD_BYTES) throw DavError.TooLarge(Limits.DOWNLOAD_BYTES)
                downloaded++
                val passphrase = credentials.load(CredentialKeys.SYNC_PASSPHRASE)
                    ?: throw ImportFailure("未设置同步口令")
                val plain = MagpieEnvelopeDecoder.decode(sealed, passphrase.toCharArray())
                val unzipped = Gzip.gunzip(plain)
                val pack = PackParser.parse(unzipped, name)
                val parsedName = UsageFileName.parse(name)!!
                if (pack.computer != parsedName.computerId || pack.day != parsedName.day) {
                    throw ImportFailure(
                        "文件内容与名称不符（包: ${pack.computer}/${pack.day}，文件名: ${parsedName.computerId}/${parsedName.day}）"
                    )
                }
                val now = System.currentTimeMillis()
                val maxT = pack.calls.maxOfOrNull { it.t } ?: 0L
                store.replaceSnapshot(
                    RemoteFileInfo(
                        fileName = name,
                        computerId = parsedName.computerId,
                        day = parsedName.day,
                        version = entry.version,
                        sizeBytes = entry.sizeBytes,
                        importedAt = now,
                        callCount = pack.calls.size,
                        parseVersion = PackParser.PARSE_VERSION,
                        failedReason = "",
                    ),
                    pack.calls,
                )
                imported++
                if (maxT > 0 && (latestRecordAt == null || maxT > latestRecordAt!!)) {
                    latestRecordAt = maxT
                    store.setMeta(MetaKeys.LATEST_RECORD_AT, maxT.toString())
                }
            } catch (e: Exception) {
                // Errors that will repeat for every file stop the pass now;
                // one bad or torn file is recorded and skipped.
                val message = e.message ?: e.javaClass.simpleName
                if (e is DavError.Auth || e is DavError.NoNetwork || e is DavError.RateLimited ||
                    e is MagpieEnvelopeDecoder.DecodeError.BadPassphrase ||
                    e is MagpieEnvelopeDecoder.DecodeError.Unsupported
                ) {
                    throw e
                }
                failed++
                failures.add("$name: $message")
                store.upsertFile(
                    RemoteFileInfo(
                        fileName = name,
                        computerId = UsageFileName.parse(name)?.computerId ?: "",
                        day = UsageFileName.parse(name)?.day ?: "",
                        version = entry.version,
                        sizeBytes = entry.sizeBytes,
                        importedAt = 0,
                        callCount = 0,
                        parseVersion = 0,
                        failedReason = message,
                    )
                )
            }
        }

        // The desktops' quota histories (.magpie-quotas, one per computer):
        // decrypt the same way, keep the points, merge across computers.
        for ((name, entry) in quotaFiles) {
            val known = store.file(name)
            if (!forceAll && known != null && known.version == entry.version && known.importedAt != 0L) continue
            try {
                val sealed = client.get(dirPath, name)
                if (sealed.size > Limits.DOWNLOAD_BYTES) throw DavError.TooLarge(Limits.DOWNLOAD_BYTES)
                downloaded++
                val passphrase = credentials.load(CredentialKeys.SYNC_PASSPHRASE)
                    ?: throw ImportFailure("未设置同步口令")
                val plain = MagpieEnvelopeDecoder.decode(sealed, passphrase.toCharArray())
                val points = QuotaPackParser.parse(Gzip.gunzip(plain))
                val source = name.removeSuffix(QUOTAS_EXT)
                if (!UsageFileName.validComputer(source)) throw ImportFailure("额度历史来源无效：$source")
                store.replaceQuotaSource(
                    source,
                    points.map {
                        dev.magpie.usage.data.db.QuotaPointEntity(
                            provider = it.provider,
                            user = it.user,
                            windowName = it.windowName,
                            at = it.at,
                            left = it.left,
                            startAt = it.startAt,
                            resetsAt = it.resetsAt,
                            source = source,
                        )
                    },
                )
                store.upsertFile(
                    RemoteFileInfo(
                        fileName = name,
                        computerId = source,
                        day = "",
                        version = entry.version,
                        sizeBytes = entry.sizeBytes,
                        importedAt = System.currentTimeMillis(),
                        callCount = points.size,
                        parseVersion = PackParser.PARSE_VERSION,
                        failedReason = "",
                    )
                )
                imported++
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (e is DavError.Auth || e is DavError.NoNetwork || e is DavError.RateLimited ||
                    e is MagpieEnvelopeDecoder.DecodeError.BadPassphrase ||
                    e is MagpieEnvelopeDecoder.DecodeError.Unsupported
                ) {
                    throw e
                }
                failed++
                failures.add("$name: $message")
            }
        }

        store.setMeta(MetaKeys.LAST_CHECKED_AT, System.currentTimeMillis().toString())
        if (failed == 0) {
            store.setMeta(MetaKeys.LAST_SUCCESSFUL_IMPORT_AT, System.currentTimeMillis().toString())
        }
        return SyncOutcome(
            checked = true,
            filesChanged = toImport.size,
            filesDownloaded = downloaded,
            filesImported = imported,
            filesFailed = failed,
            totalRemoteFiles = usageFiles.size + quotaFiles.size,
            remoteModifiedAt = remoteModifiedAt,
            latestRecordAt = latestRecordAt,
            error = failures.take(3).joinToString("\n").ifEmpty { null },
        )
    }

    /** Lists the remote without importing: the diagnostics' file inventory. */
    fun inventory(dirPath: String): List<DavEntry> {
        val account = credentials.load(CredentialKeys.ACCOUNT) ?: throw ImportFailure("未设置账号")
        val appPassword = credentials.load(CredentialKeys.APP_PASSWORD) ?: throw ImportFailure("未设置应用密码")
        return clientFactory(account, appPassword).list(dirPath)
    }

    /** Downloads and decrypts one file without importing: the test-connection check. */
    fun verifySample(dirPath: String, fileName: String): Int {
        val account = credentials.load(CredentialKeys.ACCOUNT) ?: throw ImportFailure("未设置账号")
        val appPassword = credentials.load(CredentialKeys.APP_PASSWORD) ?: throw ImportFailure("未设置应用密码")
        val pass = credentials.load(CredentialKeys.SYNC_PASSPHRASE) ?: throw ImportFailure("未设置同步口令")
        val client = clientFactory(account, appPassword)
        val sealed = client.get(dirPath, fileName)
        val plain = MagpieEnvelopeDecoder.decode(sealed, pass.toCharArray())
        val unzipped = Gzip.gunzip(plain)
        return if (fileName.endsWith(QUOTAS_EXT)) {
            QuotaPackParser.parse(unzipped).size
        } else {
            PackParser.parse(unzipped, fileName).calls.size
        }
    }
}

object CredentialKeys {
    const val ACCOUNT = "account"
    const val APP_PASSWORD = "app_password"
    const val SYNC_PASSPHRASE = "sync_passphrase"
}

/** The desktop's shared quota-history extension (davsync/usage.go:38). */
const val QUOTAS_EXT = ".magpie-quotas"

object MetaKeys {
    const val LAST_CHECKED_AT = "last_checked_at"
    const val LAST_SUCCESSFUL_IMPORT_AT = "last_successful_import_at"
    const val LATEST_RECORD_AT = "latest_record_at"
}
