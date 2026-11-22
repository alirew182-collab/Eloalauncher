package dev.eloa.launcher.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BedrockVersionEntity::class, ImportHistoryEntity::class, AddonEntity::class,
        JavaProfileEntity::class, AccountEntity::class, LogEntity::class, LaunchHistoryEntity::class,
        WorldBackupEntity::class, NotificationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun bedrockDao(): BedrockDao
    abstract fun addonDao(): AddonDao
    abstract fun profileDao(): ProfileDao
    abstract fun accountDao(): AccountDao
    abstract fun logDao(): LogDao
    abstract fun launchHistoryDao(): LaunchHistoryDao
    abstract fun worldBackupDao(): WorldBackupDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        const val NAME = "launcher.db"

        /** v3: account type (Microsoft or Ely.by). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN type TEXT NOT NULL DEFAULT 'MSA'")
            }
        }

        /** v2: world backups, notification centre, per-profile runtime/renderer/resolution scale. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE java_profiles ADD COLUMN runtimeName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE java_profiles ADD COLUMN renderer TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE java_profiles ADD COLUMN resolutionScale REAL NOT NULL DEFAULT 1.0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS world_backups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, worldKey TEXT NOT NULL, " +
                        "levelName TEXT NOT NULL, label TEXT NOT NULL, storedPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, source TEXT NOT NULL, lastPlayed INTEGER, gameVersion TEXT, gameType INTEGER, thumbnailPath TEXT, " +
                        "lastVerifiedAt INTEGER, verifyOk INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_world_backups_createdAt ON world_backups (createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_world_backups_worldKey ON world_backups (worldKey)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, " +
                        "message TEXT NOT NULL, createdAt INTEGER NOT NULL, read INTEGER NOT NULL, route TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_createdAt ON notifications (createdAt)")
            }
        }
    }
}
