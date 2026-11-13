package dev.magpie.usage.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Manifest row of one remote file. */
@Entity(tableName = "remote_files")
data class RemoteFileEntity(
    @PrimaryKey val fileName: String,
    val computerId: String,
    val day: String,
    val version: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val callCount: Int,
    val parseVersion: Int,
    val failedReason: String,
)

/** One call of one file's snapshot. */
@Entity(
    tableName = "usage_calls",
    primaryKeys = ["fileName", "ordInFile"],
)
data class UsageCallEntity(
    val fileName: String,
    val ordInFile: Int,
    val computer: String,
    val day: String,
    val t: Long,
    val agent: String,
    val provider: String,
    val model: String,
    val requested: String,
    val served: String,
    val inTok: Long,
    val outTok: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val cacheWrite1h: Long,
    val cacheUnknown: Boolean,
    val reasoning: Long,
    val effort: String,
    val ms: Long,
    val ttftMs: Long,
    val firstTextMs: Long,
    val status: Int,
    val err: String,
    val errType: String,
    val rid: String,
    val session: String,
    val sessionProvider: String,
    val host: String,
    val providerAccount: String,
    val source: String,
    val tries: Int,
    val handedOn: Boolean,
    val rejected: Boolean,
    val costUsd: Double?,
    val credits: Double?,
    val upBytes: Long,
    val downBytes: Long,
    val raw: String,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** One reading of one subscription window: left percent at a moment —
 * the phone's side of the desktop's quota history (.magpie-quotas). */
@Entity(
    tableName = "quota_points",
    primaryKeys = ["provider", "user", "windowName", "at", "source"],
)
data class QuotaPointEntity(
    val provider: String,
    val user: String,
    val windowName: String,
    val at: Long,
    val left: Double,
    val startAt: Long?,
    val resetsAt: Long?,
    /** The computer whose synced file this point came from. */
    val source: String,
)

@Dao
interface QuotaDao {
    @Query("DELETE FROM quota_points WHERE source = :source")
    fun deleteOf(source: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPoints(points: List<QuotaPointEntity>)

    @Transaction
    fun replaceSource(source: String, points: List<QuotaPointEntity>) {
        deleteOf(source)
        insertPoints(points)
    }

    @Query("SELECT * FROM quota_points ORDER BY provider, user, windowName, at")
    fun allPoints(): List<QuotaPointEntity>

    @Query("DELETE FROM quota_points")
    fun clearAll()
}

@Dao
interface UsageDao {

    @Query("SELECT * FROM remote_files ORDER BY day")
    fun files(): List<RemoteFileEntity>

    @Query("SELECT * FROM remote_files WHERE fileName = :fileName")
    fun file(fileName: String): RemoteFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertFile(file: RemoteFileEntity)

    @Query("DELETE FROM remote_files WHERE fileName = :fileName")
    fun deleteFile(fileName: String)

    @Query("DELETE FROM usage_calls WHERE fileName = :fileName")
    fun deleteCallsOf(fileName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCalls(rows: List<UsageCallEntity>)

    @Transaction
    fun replaceSnapshot(file: RemoteFileEntity, rows: List<UsageCallEntity>) {
        deleteCallsOf(file.fileName)
        upsertFile(file)
        insertCalls(rows)
    }

    @Query("SELECT * FROM usage_calls ORDER BY t, fileName, ordInFile")
    fun allCalls(): List<UsageCallEntity>

    @Query("DELETE FROM usage_calls")
    fun clearCalls()

    @Query("DELETE FROM remote_files")
    fun clearFiles()

    @Query("DELETE FROM sync_meta")
    fun clearMeta()

    @Transaction
    fun clearAll() {
        clearCalls()
        clearFiles()
        // The sync clock belongs to the archive it describes: leaving
        // "last checked / latest record" behind would keep showing a time
        // from data that no longer exists, and it only ever moves forward.
        clearMeta()
    }

    @Query("SELECT * FROM sync_meta WHERE `key` = :key")
    fun getMeta(key: String): SyncMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMeta(entity: SyncMetaEntity)
}
