package dev.eloa.launcher.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BedrockVersionEntity::class, ImportHistoryEntity::class, AddonEntity::class,
        JavaProfileEntity::class, AccountEntity::class, LogEntity::class, LaunchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun bedrockDao(): BedrockDao
    abstract fun addonDao(): AddonDao
    abstract fun profileDao(): ProfileDao
    abstract fun accountDao(): AccountDao
    abstract fun logDao(): LogDao
    abstract fun launchHistoryDao(): LaunchHistoryDao

    companion object { const val NAME = "launcher.db" }
}
