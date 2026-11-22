package dev.eloa.launcher.feature.java

import android.net.Uri
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.db.ProfileDao
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class InstallState { NOT_INSTALLED, QUEUED, INSTALLING, INSTALLED, NEEDS_PROCESSORS, FAILED }

/** Portable profile configuration (no account data, no absolute paths). */
@Serializable
data class ProfileConfig(
    val format: Int = 1,
    val name: String,
    val description: String = "",
    val iconKey: String = "grass",
    val coverKey: String = "forest",
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String? = null,
    val minMemoryMb: Int = 512,
    val maxMemoryMb: Int = 2048,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val graphicsPreset: String = "BALANCED",
    val runtimeName: String? = null,
    val renderer: String = "AUTO",
    val resolutionScale: Float = 1f,
)

fun JavaProfileEntity.toConfig() = ProfileConfig(1, name, description, iconKey, coverKey, minecraftVersion, loader, loaderVersion, minMemoryMb, maxMemoryMb, jvmArgs, gameArgs, width, height, graphicsPreset, runtimeName, renderer, resolutionScale)
val JavaProfileEntity.loaderType: ModLoaderType get() = runCatching { ModLoaderType.valueOf(loader) }.getOrDefault(ModLoaderType.VANILLA)
val JavaProfileEntity.state: InstallState get() = runCatching { InstallState.valueOf(installState) }.getOrDefault(InstallState.NOT_INSTALLED)

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val storage: LauncherStorage,
    private val logger: AppLogger,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    val profiles: Flow<List<JavaProfileEntity>> = dao.observeAll()
    fun observe(id: String) = dao.observe(id)
    suspend fun get(id: String) = dao.byId(id)
    suspend fun all() = dao.all()

    fun validateName(name: String): String? = when {
        name.isBlank() -> "Name must not be empty"
        name.length > 60 -> "Name is too long (max 60)"
        else -> null
    }

    private suspend fun uniqueDir(name: String): String {
        val base = PathSafety.sanitizeFileName(name.lowercase().replace(' ', '-'), "profile").take(40)
        var candidate = base
        var i = 2
        while (dao.countByDir(candidate) > 0 || File(storage.instancesRoot, candidate).exists()) candidate = "$base-${i++}"
        return candidate
    }

    suspend fun create(config: ProfileConfig): JavaProfileEntity = withContext(Dispatchers.IO) {
        validateName(config.name)?.let { throw IllegalArgumentException(it) }
        require(config.maxMemoryMb >= 256 && config.minMemoryMb <= config.maxMemoryMb) { "Invalid memory settings" }
        ModLoaderType.valueOf(config.loader)
        val e = JavaProfileEntity(
            id = UUID.randomUUID().toString(), name = config.name.trim(), description = config.description.trim(),
            iconKey = config.iconKey, coverKey = config.coverKey, minecraftVersion = config.minecraftVersion,
            loader = config.loader, loaderVersion = config.loaderVersion, gameDirName = uniqueDir(config.name),
            minMemoryMb = config.minMemoryMb, maxMemoryMb = config.maxMemoryMb, jvmArgs = config.jvmArgs, gameArgs = config.gameArgs,
            width = config.width, height = config.height, graphicsPreset = config.graphicsPreset, createdAt = System.currentTimeMillis(),
            runtimeName = config.runtimeName, renderer = config.renderer, resolutionScale = config.resolutionScale.coerceIn(0.25f, 1f),
        )
        storage.instance(e.gameDirName).ensure()
        dao.upsert(e)
        logger.i("Profiles", "Created profile ${e.name}", mapOf("version" to e.minecraftVersion, "loader" to e.loader))
        e
    }

    /** Updates editable fields; changing version/loader resets the install state. */
    suspend fun update(id: String, config: ProfileConfig) {
        val old = dao.byId(id) ?: throw IOException("Profile not found")
        validateName(config.name)?.let { throw IllegalArgumentException(it) }
        require(config.maxMemoryMb >= 256 && config.minMemoryMb <= config.maxMemoryMb) { "Invalid memory settings" }
        val changedGame = old.minecraftVersion != config.minecraftVersion || old.loader != config.loader || old.loaderVersion != config.loaderVersion
        dao.upsert(old.copy(
            name = config.name.trim(), description = config.description.trim(), iconKey = config.iconKey, coverKey = config.coverKey,
            minecraftVersion = config.minecraftVersion, loader = config.loader, loaderVersion = config.loaderVersion,
            minMemoryMb = config.minMemoryMb, maxMemoryMb = config.maxMemoryMb, jvmArgs = config.jvmArgs, gameArgs = config.gameArgs,
            width = config.width, height = config.height, graphicsPreset = config.graphicsPreset,
            runtimeName = config.runtimeName, renderer = config.renderer, resolutionScale = config.resolutionScale.coerceIn(0.25f, 1f),
            installState = if (changedGame) InstallState.NOT_INSTALLED.name else old.installState,
            launchVersionId = if (changedGame) null else old.launchVersionId,
        ))
    }

    suspend fun clone(id: String, withFiles: Boolean): JavaProfileEntity = withContext(Dispatchers.IO) {
        val src = dao.byId(id) ?: throw IOException("Profile not found")
        val copy = create(src.toConfig().copy(name = (src.name + " (copy)").take(60)))
        if (withFiles) {
            val from = storage.instance(src.gameDirName).gameDir
            val to = storage.instance(copy.gameDirName).gameDir
            from.listFiles()?.filter { it.name != ".eloa-backups" }?.forEach { FileOps.copy(it, File(to, it.name), overwrite = true) }
        }
        val final = copy.copy(installState = src.installState, launchVersionId = src.launchVersionId, pendingProcessors = src.pendingProcessors)
        dao.upsert(final)
        final
    }

    suspend fun delete(id: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext
        dao.delete(e)
        if (deleteFiles) storage.instance(e.gameDirName).gameDir.deleteRecursively()
        logger.i("Profiles", "Deleted profile ${e.name}", mapOf("filesDeleted" to deleteFiles.toString()))
    }

    suspend fun markPlayed(id: String) { dao.byId(id)?.let { dao.upsert(it.copy(lastPlayedAt = System.currentTimeMillis())) } }

    suspend fun setInstallState(id: String, state: InstallState, error: String? = null) = dao.setInstallState(id, state.name, error)

    suspend fun setInstalled(id: String, launchVersionId: String, pendingProcessors: Int) {
        dao.byId(id)?.let {
            dao.upsert(it.copy(launchVersionId = launchVersionId, pendingProcessors = pendingProcessors, lastError = null,
                installState = (if (pendingProcessors > 0) InstallState.NEEDS_PROCESSORS else InstallState.INSTALLED).name))
        }
    }

    /** Launch-time options edited on the game settings page (validated). */
    suspend fun updateLaunchOptions(id: String, transform: (JavaProfileEntity) -> JavaProfileEntity) {
        val old = dao.byId(id) ?: throw IOException("Profile not found")
        val n = transform(old)
        require(n.maxMemoryMb >= 256 && n.minMemoryMb in 128..n.maxMemoryMb) { "Invalid memory settings" }
        dev.eloa.launcher.domain.java.launch.LaunchPlanBuilder.validateExtraJvmArgs(n.jvmArgs.split(' ').filter { it.isNotBlank() }).firstOrNull()?.let {
            throw IllegalArgumentException("JVM argument not allowed: $it")
        }
        dao.upsert(old.copy(
            minMemoryMb = n.minMemoryMb, maxMemoryMb = n.maxMemoryMb, jvmArgs = n.jvmArgs.trim(), gameArgs = n.gameArgs.trim(),
            width = n.width, height = n.height, runtimeName = n.runtimeName, renderer = n.renderer,
            resolutionScale = n.resolutionScale.coerceIn(0.25f, 1f), graphicsPreset = n.graphicsPreset,
        ))
    }

    suspend fun setMemory(id: String, maxMb: Int) { dao.byId(id)?.let { dao.upsert(it.copy(maxMemoryMb = maxMb, minMemoryMb = minOf(it.minMemoryMb, maxMb))) } }

    fun encode(config: ProfileConfig): String = json.encodeToString(ProfileConfig.serializer(), config)
    fun decode(text: String): ProfileConfig = try { json.decodeFromString(ProfileConfig.serializer(), text) } catch (e: Exception) {
        throw IOException("Not a valid profile configuration: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    suspend fun exportConfig(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Profile not found")
        val tmp = File(storage.importTemp, "profile.json").apply { parentFile?.mkdirs(); writeText(encode(e.toConfig())) }
        try { storage.exportTo(tmp, uri) } finally { tmp.delete() }
    }

    suspend fun importConfig(uri: Uri): JavaProfileEntity = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 256 * 1024)
        try { create(decode(tmp.readText())) } finally { tmp.delete() }
    }
}
