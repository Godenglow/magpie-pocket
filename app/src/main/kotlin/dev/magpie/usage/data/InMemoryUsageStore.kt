package dev.magpie.usage.data

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.model.RemoteFileInfo
import dev.magpie.usage.data.db.QuotaPointEntity

/** In-memory store for unit tests: same contract, no SQLite. */
class InMemoryUsageStore : UsageStore {

    val filesMap = LinkedHashMap<String, RemoteFileInfo>()
    val callsByFile = LinkedHashMap<String, MutableList<CallRow>>()
    val meta = HashMap<String, String>()
    val quotaPointsBySource = LinkedHashMap<String, MutableList<QuotaPointEntity>>()
    var replaceCount = 0

    override fun files(): List<RemoteFileInfo> = filesMap.values.toList()

    override fun file(fileName: String): RemoteFileInfo? = filesMap[fileName]

    override fun upsertFile(file: RemoteFileInfo) {
        filesMap[file.fileName] = file
    }

    override fun removeFile(fileName: String) {
        filesMap.remove(fileName)
        callsByFile.remove(fileName)
    }

    override fun replaceSnapshot(file: RemoteFileInfo, rows: List<CallRow>) {
        replaceCount++
        callsByFile[file.fileName] = rows.toMutableList()
        filesMap[file.fileName] = file
    }

    override fun allCalls(): List<CallRow> =
        callsByFile.values.flatten().sortedWith(compareBy({ it.t }, { it.fileName }, { it.ordInFile }))

    override fun clearAll() {
        filesMap.clear()
        callsByFile.clear()
        quotaPointsBySource.clear()
        // The sync clock describes the archive that was just removed.
        meta.clear()
    }

    override fun replaceQuotaSource(source: String, points: List<QuotaPointEntity>) {
        quotaPointsBySource[source] = points.toMutableList()
    }

    override fun quotaLines(): List<QuotaLineData> {
        val merged = LinkedHashMap<Triple<String, String, String>, LinkedHashMap<Long, QuotaPointEntity>>()
        for (points in quotaPointsBySource.values) {
            for (p in points) {
                val key = Triple(p.provider, p.user, p.windowName)
                merged.getOrPut(key) { LinkedHashMap() }[p.at] = p
            }
        }
        return merged.map { (k, byAt) ->
            QuotaLineData(
                k.first, k.second, k.third,
                byAt.values.sortedBy { it.at }
                    .map { Triple(it.at, it.left, it.startAt to it.resetsAt) },
            )
        }
    }

    override fun setMeta(key: String, value: String) {
        meta[key] = value
    }

    override fun getMeta(key: String): String? = meta[key]
}
