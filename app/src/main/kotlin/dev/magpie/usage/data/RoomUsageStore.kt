package dev.magpie.usage.data

import android.content.Context
import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.model.RemoteFileInfo
import dev.magpie.usage.data.db.AppDatabase
import dev.magpie.usage.data.db.QuotaPointEntity
import dev.magpie.usage.data.db.RemoteFileEntity
import dev.magpie.usage.data.db.SyncMetaEntity
import dev.magpie.usage.data.db.UsageCallEntity

/** One subscription window's readings, for the 概览 quota cards. */
data class QuotaLineData(
    val provider: String,
    val user: String,
    val windowName: String,
    /** (at epoch ms, left percent, startAt?, resetsAt?) oldest first. */
    val points: List<Triple<Long, Double, Pair<Long?, Long?>>>,
)

/** Room-backed store: the phone's own archive of imported snapshots.
 *  [dbName] is the connection's database file (see AppDatabase.get): one
 *  archive per WebDAV account/folder, so switching never mixes them. */
class RoomUsageStore(context: Context, dbName: String = AppDatabase.DEFAULT_NAME) : UsageStore {

    private val db = AppDatabase.get(context, dbName)
    private val dao = db.usageDao()
    private val quotaDao = db.quotaDao()

    /** Replaces one computer's quota points and returns the merged history. */
    override fun replaceQuotaSource(source: String, points: List<QuotaPointEntity>) {
        quotaDao.replaceSource(source, points)
    }

    /** Merged history by account (provider|user), windows oldest first. */
    override fun quotaLines(): List<QuotaLineData> {
        val out = LinkedHashMap<Triple<String, String, String>, MutableList<Triple<Long, Double, Pair<Long?, Long?>>>>()
        for (p in quotaDao.allPoints()) {
            val key = Triple(p.provider, p.user, p.windowName)
            out.getOrPut(key) { mutableListOf() }.add(Triple(p.at, p.left, p.startAt to p.resetsAt))
        }
        return out.map { (k, points) ->
            QuotaLineData(k.first, k.second, k.third, points.sortedBy { it.first })
        }
    }

    fun clearQuota() = quotaDao.clearAll()

    override fun files(): List<RemoteFileInfo> = dao.files().map { it.toModel() }

    override fun file(fileName: String): RemoteFileInfo? = dao.file(fileName)?.toModel()

    override fun upsertFile(file: RemoteFileInfo) {
        dao.upsertFile(file.toEntity())
    }

    override fun removeFile(fileName: String) {
        dao.deleteCallsOf(fileName)
        dao.deleteFile(fileName)
    }

    override fun replaceSnapshot(file: RemoteFileInfo, rows: List<CallRow>) {
        dao.replaceSnapshot(file.toEntity(), rows.map { it.toEntity() })
    }

    override fun allCalls(): List<CallRow> = dao.allCalls().map { it.toModel() }

    override fun clearAll() {
        dao.clearAll()
        clearQuota()
    }

    override fun setMeta(key: String, value: String) = dao.putMeta(SyncMetaEntity(key, value))

    override fun getMeta(key: String): String? = dao.getMeta(key)?.value
}

internal fun RemoteFileEntity.toModel() = RemoteFileInfo(
    fileName = fileName,
    computerId = computerId,
    day = day,
    version = version,
    sizeBytes = sizeBytes,
    importedAt = importedAt,
    callCount = callCount,
    parseVersion = parseVersion,
    failedReason = failedReason,
)

internal fun RemoteFileInfo.toEntity() = RemoteFileEntity(
    fileName = fileName,
    computerId = computerId,
    day = day,
    version = version,
    sizeBytes = sizeBytes,
    importedAt = importedAt,
    callCount = callCount,
    parseVersion = parseVersion,
    failedReason = failedReason,
)

internal fun UsageCallEntity.toModel() = CallRow(
    fileName = fileName,
    ordInFile = ordInFile,
    computer = computer,
    day = day,
    t = t,
    agent = agent,
    provider = provider,
    model = model,
    requested = requested,
    served = served,
    inTok = inTok,
    outTok = outTok,
    cacheRead = cacheRead,
    cacheWrite = cacheWrite,
    cacheWrite1h = cacheWrite1h,
    cacheUnknown = cacheUnknown,
    reasoning = reasoning,
    effort = effort,
    ms = ms,
    ttftMs = ttftMs,
    firstTextMs = firstTextMs,
    status = status,
    err = err,
    errType = errType,
    rid = rid,
    session = session,
    sessionProvider = sessionProvider,
    host = host,
    providerAccount = providerAccount,
    source = source,
    tries = tries,
    handedOn = handedOn,
    rejected = rejected,
    costUsd = costUsd,
    credits = credits,
    upBytes = upBytes,
    downBytes = downBytes,
    raw = raw,
)

internal fun CallRow.toEntity() = UsageCallEntity(
    fileName = fileName,
    ordInFile = ordInFile,
    computer = computer,
    day = day,
    t = t,
    agent = agent,
    provider = provider,
    model = model,
    requested = requested,
    served = served,
    inTok = inTok,
    outTok = outTok,
    cacheRead = cacheRead,
    cacheWrite = cacheWrite,
    cacheWrite1h = cacheWrite1h,
    cacheUnknown = cacheUnknown,
    reasoning = reasoning,
    effort = effort,
    ms = ms,
    ttftMs = ttftMs,
    firstTextMs = firstTextMs,
    status = status,
    err = err,
    errType = errType,
    rid = rid,
    session = session,
    sessionProvider = sessionProvider,
    host = host,
    providerAccount = providerAccount,
    source = source,
    tries = tries,
    handedOn = handedOn,
    rejected = rejected,
    costUsd = costUsd,
    credits = credits,
    upBytes = upBytes,
    downBytes = downBytes,
    raw = raw,
)
