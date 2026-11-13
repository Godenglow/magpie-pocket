package dev.magpie.usage.data

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.model.RemoteFileInfo
import dev.magpie.usage.data.db.QuotaPointEntity

/**
 * Where imported snapshots live. Every import replaces a file's rows in one
 * transaction — never an append — so the same file read twice cannot count
 * twice, and a bad file cannot wipe a day's history.
 */
interface UsageStore {
    fun files(): List<RemoteFileInfo>
    fun file(fileName: String): RemoteFileInfo?
    fun upsertFile(file: RemoteFileInfo)
    fun removeFile(fileName: String)

    /** Atomic snapshot replace: delete [fileName]'s rows, insert the new ones. */
    fun replaceSnapshot(file: RemoteFileInfo, rows: List<CallRow>)

    /** All rows, oldest first. */
    fun allCalls(): List<CallRow>

    fun clearAll()

    /** One computer's quota history replaced; merged across sources on read. */
    fun replaceQuotaSource(source: String, points: List<QuotaPointEntity>)
    fun quotaLines(): List<QuotaLineData>

    fun setMeta(key: String, value: String)
    fun getMeta(key: String): String?
}
