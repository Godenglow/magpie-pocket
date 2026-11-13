package dev.magpie.usage.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RemoteFileEntity::class, UsageCallEntity::class, SyncMetaEntity::class, QuotaPointEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun quotaDao(): QuotaDao

    companion object {
        /** The archive the first configured connection owns (pre-isolation). */
        const val DEFAULT_NAME = "magpie-usage.db"

        private val instances = HashMap<String, AppDatabase>()

        /**
         * One database file per WebDAV connection. Two accounts (or two
         * remote folders) keep separate archives, so switching never shows
         * one account's calls, costs or quotas under the other's name.
         */
        fun get(context: Context, name: String = DEFAULT_NAME): AppDatabase =
            synchronized(instances) {
                instances[name] ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    name,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instances[name] = it }
            }

        /** v2 adds session_provider (pricing a session-unknown row). */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_calls ADD COLUMN sessionProvider TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 adds host/providerAccount (the 账号 dimension) and quota points. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_calls ADD COLUMN host TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE usage_calls ADD COLUMN providerAccount TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quota_points (" +
                        "provider TEXT NOT NULL, user TEXT NOT NULL, windowName TEXT NOT NULL, " +
                        "at INTEGER NOT NULL, left REAL NOT NULL, startAt INTEGER, resetsAt INTEGER, " +
                        "source TEXT NOT NULL, " +
                        "PRIMARY KEY(provider, user, windowName, at, source))"
                )
            }
        }
    }
}
