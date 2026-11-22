package dev.eloa.launcher.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BedrockDao {
    @Query("SELECT * FROM bedrock_versions ORDER BY versionCode DESC")
    fun observeAll(): Flow<List<BedrockVersionEntity>>

    @Query("SELECT * FROM bedrock_versions ORDER BY versionCode DESC")
    suspend fun all(): List<BedrockVersionEntity>

    @Query("SELECT * FROM bedrock_versions WHERE id = :id")
    suspend fun byId(id: Long): BedrockVersionEntity?

    @Query("SELECT * FROM bedrock_versions WHERE baseSha256 = :sha")
    suspend fun bySha(sha: String): BedrockVersionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: BedrockVersionEntity): Long

    @Update suspend fun update(e: BedrockVersionEntity)
    @Delete suspend fun delete(e: BedrockVersionEntity)

    @Insert suspend fun insertHistory(h: ImportHistoryEntity)

    @Query("SELECT * FROM import_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeHistory(limit: Int = 200): Flow<List<ImportHistoryEntity>>
}

@Dao
interface AddonDao {
    @Query("SELECT * FROM addons ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons")
    suspend fun all(): List<AddonEntity>

    @Query("SELECT * FROM addons WHERE id = :id")
    suspend fun byId(id: Long): AddonEntity?

    @Query("SELECT * FROM addons WHERE sha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): AddonEntity?

    @Insert suspend fun insert(e: AddonEntity): Long
    @Update suspend fun update(e: AddonEntity)
    @Delete suspend fun delete(e: AddonEntity)

    @Query("SELECT * FROM addons ORDER BY importedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AddonEntity>>
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM java_profiles ORDER BY COALESCE(lastPlayedAt, createdAt) DESC")
    fun observeAll(): Flow<List<JavaProfileEntity>>

    @Query("SELECT * FROM java_profiles ORDER BY name")
    suspend fun all(): List<JavaProfileEntity>

    @Query("SELECT * FROM java_profiles WHERE id = :id")
    suspend fun byId(id: String): JavaProfileEntity?

    @Query("SELECT * FROM java_profiles WHERE id = :id")
    fun observe(id: String): Flow<JavaProfileEntity?>

    @Upsert suspend fun upsert(e: JavaProfileEntity)
    @Delete suspend fun delete(e: JavaProfileEntity)

    @Query("SELECT COUNT(*) FROM java_profiles WHERE gameDirName = :dir")
    suspend fun countByDir(dir: String): Int

    @Query("UPDATE java_profiles SET installState = :state, lastError = :error WHERE id = :id")
    suspend fun setInstallState(id: String, state: String, error: String?)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY addedAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE active = 1 LIMIT 1")
    suspend fun active(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Upsert suspend fun upsert(e: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE accounts SET active = (id = :id)")
    suspend fun setActive(id: String)
}

@Dao
interface LogDao {
    @Query("""SELECT * FROM logs WHERE level IN (:levels)
        AND (:query = '' OR event LIKE '%' || :query || '%' OR component LIKE '%' || :query || '%' OR errorType LIKE '%' || :query || '%')
        ORDER BY timestamp DESC, id DESC LIMIT :limit""")
    fun observe(levels: List<String>, query: String, limit: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs ORDER BY timestamp ASC, id ASC")
    suspend fun allAscending(): List<LogEntity>

    @Insert suspend fun insertAll(items: List<LogEntity>)

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int

    @Query("DELETE FROM logs WHERE id IN (SELECT id FROM logs ORDER BY timestamp ASC, id ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM logs") suspend fun clear()

    @Transaction
    suspend fun insertAndTrim(items: List<LogEntity>, max: Int) {
        insertAll(items)
        val excess = count() - max
        if (excess > 0) deleteOldest(excess)
    }

    @Query("SELECT COUNT(*) FROM logs WHERE level IN ('ERROR','CRITICAL') AND timestamp > :since")
    fun observeErrorCount(since: Long): Flow<Int>
}

@Dao
interface LaunchHistoryDao {
    @Insert suspend fun insert(e: LaunchHistoryEntity)

    @Query("SELECT * FROM launch_history ORDER BY launchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LaunchHistoryEntity>>
}

@Dao
interface WorldBackupDao {
    @Query("SELECT * FROM world_backups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorldBackupEntity>>

    @Query("SELECT * FROM world_backups WHERE id = :id")
    suspend fun byId(id: Long): WorldBackupEntity?

    @Query("SELECT * FROM world_backups WHERE sha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): WorldBackupEntity?

    @Insert suspend fun insert(e: WorldBackupEntity): Long
    @Update suspend fun update(e: WorldBackupEntity)
    @Delete suspend fun delete(e: WorldBackupEntity)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT :limit")
    fun observe(limit: Int = 200): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Insert suspend fun insert(e: NotificationEntity): Long

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("UPDATE notifications SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notifications")
    suspend fun clear()

    /** Keeps the newest [keep] entries. */
    @Query("DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM notifications WHERE type = :type AND createdAt > :since")
    suspend fun countSince(type: String, since: Long): Int
}
