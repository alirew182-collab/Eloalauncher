package dev.eloa.launcher.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An imported Bedrock APK set stored in the launcher's private library. */
@Entity(tableName = "bedrock_versions", indices = [Index(value = ["baseSha256"], unique = true)])
data class BedrockVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val abis: String,
    val minSdk: Int,
    val targetSdk: Int?,
    val totalSize: Long,
    val baseSha256: String,
    val certSha256: String,
    val signatureValid: Boolean,
    val compatStatus: String,
    val compatReasons: String,
    val storageDir: String,
    val apkFiles: String,
    val importedAt: Long,
    val lastVerifiedAt: Long? = null,
    val integrityOk: Boolean = true,
    val lastLaunchedAt: Long? = null,
    val notes: String = "",
)

@Entity(tableName = "import_history", indices = [Index("timestamp")])
data class ImportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val fileName: String,
    val timestamp: Long,
    val success: Boolean,
    val message: String,
)

/** Bedrock add-on / world / structure in the launcher library. */
@Entity(tableName = "addons", indices = [Index("primaryUuid"), Index("kind")])
data class AddonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val kind: String,
    /** Comma separated pack types (RESOURCE, BEHAVIOR …). */
    val packTypes: String,
    val primaryUuid: String?,
    /** JSON array of packs: uuid, version, name, type, minEngine, dependencies. */
    val packsJson: String,
    val version: String?,
    val minEngineVersion: String?,
    val fileName: String,
    val storedPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAt: Long,
    val enabled: Boolean = true,
    val category: String = "",
    val findings: String = "",
    val hasErrors: Boolean = false,
)

/** Java Edition game profile (instance). */
@Entity(tableName = "java_profiles")
data class JavaProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val iconKey: String = "grass",
    val coverKey: String = "forest",
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String? = null,
    /** versions/<id> to launch (vanilla id or loader profile id) once installed. */
    val launchVersionId: String? = null,
    val gameDirName: String,
    val minMemoryMb: Int = 512,
    val maxMemoryMb: Int = 2048,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val graphicsPreset: String = "BALANCED",
    val createdAt: Long,
    val lastPlayedAt: Long? = null,
    val installState: String = "NOT_INSTALLED",
    val pendingProcessors: Int = 0,
    val lastError: String? = null,
    /** Folder name of the chosen Java runtime; null = pick automatically for the version. */
    @ColumnInfo(defaultValue = "NULL") val runtimeName: String? = null,
    /** RendererId name or AUTO. */
    @ColumnInfo(defaultValue = "AUTO") val renderer: String = "AUTO",
    /** Render resolution scale handed to the runtime backend (0.25..1). */
    @ColumnInfo(defaultValue = "1.0") val resolutionScale: Float = 1f,
)

@Entity(tableName = "accounts")
data class AccountEntity(
    /** Minecraft profile UUID. */
    @PrimaryKey val id: String,
    val username: String,
    val xuid: String?,
    /** AES-GCM encrypted JSON with tokens (key lives in Android Keystore). */
    val encryptedTokens: ByteArray,
    val minecraftTokenExpiresAt: Long,
    val active: Boolean,
    val skinUrl: String?,
    val addedAt: Long,
    val lastRefreshAt: Long,
) {
    override fun equals(other: Any?) = other is AccountEntity && other.id == id && other.lastRefreshAt == lastRefreshAt && other.active == active
    override fun hashCode() = id.hashCode()
    override fun toString() = "AccountEntity(id=$id, username=$username, tokens=***)"
}

@Entity(tableName = "logs", indices = [Index("timestamp"), Index("level")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val component: String,
    val event: String,
    val errorType: String?,
    val stackTrace: String?,
    val context: String?,
)

@Entity(tableName = "launch_history", indices = [Index("launchedAt")])
data class LaunchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val edition: String,
    val targetId: String,
    val title: String,
    val subtitle: String,
    val launchedAt: Long,
    val success: Boolean,
    val message: String?,
)

/** A Bedrock world backup stored as .mcworld in the launcher's private storage. */
@Entity(tableName = "world_backups", indices = [Index("createdAt"), Index("worldKey")])
data class WorldBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id of the source world (folder name in minecraftWorlds), empty for imports. */
    val worldKey: String,
    val levelName: String,
    /** User-editable label of this backup. */
    val label: String,
    val storedPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
    /** SAF_FOLDER, IMPORT or LIBRARY. */
    val source: String,
    val lastPlayed: Long?,
    val gameVersion: String?,
    val gameType: Int?,
    val thumbnailPath: String?,
    val lastVerifiedAt: Long? = null,
    val verifyOk: Boolean = true,
)

/** In-app notification centre entry. */
@Entity(tableName = "notifications", indices = [Index("createdAt")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** DOWNLOAD_DONE, INSTALL_FAILED, LOW_STORAGE, INCOMPATIBLE_MOD, UPDATE_AVAILABLE, DAMAGED_FILE, OVERHEATING, INFO */
    val type: String,
    val title: String,
    val message: String,
    val createdAt: Long,
    val read: Boolean = false,
    /** Navigation route to open from the notification, if any. */
    val route: String? = null,
)
