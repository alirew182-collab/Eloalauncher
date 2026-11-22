package dev.eloa.launcher.feature.worlds

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.WorldBackupDao
import dev.eloa.launcher.core.data.db.WorldBackupEntity
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.apk.KnownPackages
import dev.eloa.launcher.domain.bedrock.world.WorldArchive
import dev.eloa.launcher.domain.bedrock.world.WorldArchiveException
import dev.eloa.launcher.domain.bedrock.world.WorldFile
import dev.eloa.launcher.domain.bedrock.world.WorldProblem
import dev.eloa.launcher.feature.common.SafTree
import dev.eloa.launcher.feature.notifications.NoticeType
import dev.eloa.launcher.feature.notifications.NotificationCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A world folder found in the user-granted Minecraft folder. */
data class SourceWorld(val key: String, val name: String, val uri: Uri)

class NoWorldFolderException : IOException("Choose the Minecraft folder (games/com.mojang or minecraftWorlds) first.")

/**
 * Bedrock world backups. Worlds are read only from a folder the user granted through the Storage
 * Access Framework; Minecraft's private app storage is never accessed. Backups are standard
 * `.mcworld` files, so they can also be imported by the game itself.
 */
@Singleton
class WorldBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: WorldBackupDao,
    private val storage: LauncherStorage,
    private val settings: SettingsRepository,
    private val notifications: NotificationCenter,
    private val logger: AppLogger,
) {
    val backups: Flow<List<WorldBackupEntity>> = dao.observeAll()
    private val dir get() = File(storage.backups, "worlds").apply { mkdirs() }

    private suspend fun tree(): SafTree? = settings.current().mojangFolderUri.takeIf { it.isNotEmpty() }?.let { SafTree(context.contentResolver, it.toUri()) }

    /** minecraftWorlds folder inside the granted tree (the tree may be games/, com.mojang/ or minecraftWorlds/ itself). */
    private fun worldsDir(t: SafTree): Uri? {
        val root = t.root()
        val top = t.list(root)
        if (top.any { it.isDir && t.find(it.uri, "level.dat") != null }) return root
        top.firstOrNull { it.isDir && it.name == "minecraftWorlds" }?.let { return it.uri }
        top.firstOrNull { it.isDir && it.name == "com.mojang" }?.let { m -> t.find(m.uri, "minecraftWorlds")?.takeIf { it.isDir }?.let { return it.uri } }
        return null
    }

    suspend fun folderGranted(): Boolean = tree() != null

    suspend fun setFolder(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        settings.update { it.copy(mojangFolderUri = uri.toString()) }
    }

    suspend fun sourceWorlds(): List<SourceWorld> = withContext(Dispatchers.IO) {
        val t = tree() ?: return@withContext emptyList()
        val wd = worldsDir(t) ?: return@withContext emptyList()
        t.list(wd).filter { it.isDir }.mapNotNull { d ->
            if (t.find(d.uri, "level.dat") == null) return@mapNotNull null
            val name = t.find(d.uri, "levelname.txt")?.let { f -> runCatching { t.open(f.uri).use { it.readBytes().decodeToString().trim() } }.getOrNull() }
            SourceWorld(d.name, name?.takeIf { it.isNotEmpty() } ?: d.name, d.uri)
        }.sortedBy { it.name.lowercase() }
    }

    /** Backs up a world from the granted folder. Returns null if an identical backup already exists. */
    suspend fun backup(world: SourceWorld, onProgress: (Long) -> Unit = {}): WorldBackupEntity? = withContext(Dispatchers.IO) {
        val t = tree() ?: throw NoWorldFolderException()
        val files = t.walkFiles(world.uri).map { (path, doc) -> WorldFile(path, doc.size) { t.open(doc.uri) } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(dir, "${PathSafety.sanitizeFileName(world.key, "world")}_$stamp.mcworld")
        val ctx = currentCoroutineContext()
        val sha = WorldArchive.pack(files, out, isCancelled = { !ctx.isActive }, onProgress = onProgress)
        register(out, sha, world.key, "SAF_FOLDER", null)
    }

    /** Imports a `.mcworld` chosen by the user (the original file is only read). */
    suspend fun import(uri: Uri): WorldBackupEntity? = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 8L * 1024 * 1024 * 1024)
        try {
            val v = WorldArchive.verify(tmp, null)
            if (!v.ok && v.problems.any { it != WorldProblem.NO_DB }) throw WorldArchiveException(v.problems.first(), "Not a valid Bedrock world: ${v.problems.joinToString()}")
            val name = PathSafety.sanitizeFileName(storage.displayName(uri).substringBeforeLast('.'), "world")
            val out = File(dir, "${name}_${System.currentTimeMillis()}.mcworld")
            if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
            register(out, v.sha256 ?: "", "", "IMPORT", storage.displayName(uri))
        } finally { tmp.delete() }
    }

    private suspend fun register(file: File, sha: String, key: String, source: String, label: String?): WorldBackupEntity? {
        dao.bySha(sha)?.let { file.delete(); return null }
        val meta = WorldArchive.inspect(file)
        val thumb = meta.thumbnail?.let { bytes -> File(dir, file.nameWithoutExtension + ".jpg").apply { writeBytes(bytes) }.absolutePath }
        val e = WorldBackupEntity(
            worldKey = key, levelName = meta.levelName, label = label?.substringBeforeLast('.') ?: meta.levelName,
            storedPath = file.absolutePath, sizeBytes = file.length(), sha256 = sha, createdAt = System.currentTimeMillis(), source = source,
            lastPlayed = meta.lastPlayed, gameVersion = meta.lastOpenedWithVersion, gameType = meta.gameType, thumbnailPath = thumb,
            lastVerifiedAt = System.currentTimeMillis(), verifyOk = true,
        )
        val id = dao.insert(e)
        logger.i("Worlds", "World backup created", mapOf("world" to meta.levelName, "source" to source))
        return e.copy(id = id)
    }

    suspend fun verify(id: Long): Boolean = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Backup not found")
        val f = File(e.storedPath)
        val ok = f.isFile && WorldArchive.verify(f, e.sha256).ok
        dao.update(e.copy(lastVerifiedAt = System.currentTimeMillis(), verifyOk = ok))
        if (!ok) notifications.post(NoticeType.DAMAGED_FILE, R.string.notice_damaged_title, context.getString(R.string.notice_damaged_world, e.label), "worlds")
        ok
    }

    /**
     * Restores into the granted minecraftWorlds folder. If a world with the same folder already
     * exists it is backed up first, then replaced. The archive is verified before anything is written.
     */
    suspend fun restore(id: Long): String = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Backup not found")
        val f = File(e.storedPath)
        val check = WorldArchive.verify(f, e.sha256)
        if (!check.ok) {
            dao.update(e.copy(lastVerifiedAt = System.currentTimeMillis(), verifyOk = false))
            throw WorldArchiveException(check.problems.first(), "The backup is damaged (${check.problems.joinToString()}); nothing was changed.")
        }
        val t = tree() ?: throw NoWorldFolderException()
        val wd = worldsDir(t) ?: t.dirPath("minecraftWorlds")
        val key = e.worldKey.ifEmpty { PathSafety.sanitizeFileName(e.label, "world").take(40) + "_" + e.id }
        t.find(wd, key)?.let { existing ->
            if (existing.isDir) {
                backup(SourceWorld(key, e.levelName, existing.uri))
                if (!t.delete(existing.uri)) throw IOException("Cannot replace the existing world folder")
            }
        }
        val target = t.dir(wd, key)
        val cache = HashMap<String, Uri>()
        WorldArchive.restore(f) { path, input ->
            val parts = path.split('/')
            var parent = target
            var rel = ""
            for (seg in parts.dropLast(1)) {
                rel = if (rel.isEmpty()) seg else "$rel/$seg"
                val p = parent
                parent = cache.getOrPut(rel) { t.dir(p, seg) }
            }
            t.createFile(parent, parts.last()).use { out -> input.copyTo(out) }
        }
        logger.i("Worlds", "World restored", mapOf("world" to e.levelName))
        key
    }

    /** Official import route: hands the .mcworld to Minecraft, which imports it itself. */
    suspend fun openInMinecraftIntent(id: Long): Intent = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Backup not found")
        val share = File(storage.shareDir, PathSafety.sanitizeFileName(e.label, "world") + ".mcworld").apply { parentFile?.mkdirs() }
        File(e.storedPath).copyTo(share, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", share)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
            .setPackage(KnownPackages.MINECRAFT_BEDROCK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun export(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: throw IOException("Backup not found")
        storage.exportTo(File(e.storedPath), uri)
    }

    suspend fun rename(id: Long, label: String) {
        val e = dao.byId(id) ?: return
        dao.update(e.copy(label = label.trim().take(80).ifEmpty { e.levelName }))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@withContext
        File(e.storedPath).delete()
        e.thumbnailPath?.let { File(it).delete() }
        dao.delete(e)
    }
}
