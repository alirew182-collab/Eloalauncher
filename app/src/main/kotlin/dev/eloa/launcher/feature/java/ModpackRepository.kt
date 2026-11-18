package dev.eloa.launcher.feature.java

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.modpack.ModpackException
import dev.eloa.launcher.domain.java.modpack.Mrpack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ModpackPreview(val name: String, val summary: String?, val minecraftVersion: String, val loader: String, val loaderVersion: String?, val fileCount: Int, val format: String)

/**
 * Modpacks ("Сборки"):
 *  - .eloapack: this launcher's own export (profile.json + mods/config/resourcepacks/shaderpacks[/saves]).
 *  - .mrpack: Modrinth format; files are downloaded only from hosts allowed by its spec and verified by SHA-1.
 */
@Singleton
class ModpackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: LauncherStorage,
    private val profiles: ProfileRepository,
    private val logger: AppLogger,
) {
    private val exportFolders = listOf("mods", "config", "resourcepacks", "shaderpacks", "options.txt")

    suspend fun export(p: JavaProfileEntity, uri: Uri, includeWorlds: Boolean) = withContext(Dispatchers.IO) {
        val game = storage.instance(p.gameDirName).gameDir
        val tmp = File(storage.importTemp, "export.eloapack").apply { parentFile?.mkdirs() }
        var count = 0
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST)); z.write(profiles.encode(p.toConfig()).toByteArray()); z.closeEntry()
            val roots = exportFolders + if (includeWorlds) listOf("saves") else emptyList()
            roots.map { File(game, it) }.filter { it.exists() }.forEach { root ->
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("files/" + f.relativeTo(game).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); count++
                }
            }
        }
        try { storage.exportTo(tmp, uri) } finally { tmp.delete() }
        logger.i("Modpacks", "Exported ${p.name}", mapOf("files" to count.toString()))
        count
    }

    /** Copies the pack to private storage and reads its description without installing anything. */
    suspend fun preview(uri: Uri): Pair<File, ModpackPreview> = withContext(Dispatchers.IO) {
        val name = storage.displayName(uri)
        val tmp = storage.copyToTemp(uri, 2L * 1024 * 1024 * 1024)
        try {
            val preview = when {
                name.endsWith(".mrpack", true) -> Mrpack.read(tmp).let { ModpackPreview(it.name, it.summary, it.minecraftVersion, it.loader.name, it.loaderVersion, it.files.size, "Modrinth") }
                else -> SafeZip.open(tmp).use { z ->
                    val cfg = profiles.decode(z.readText(MANIFEST) ?: throw ModpackException("Not an .eloapack (profile.json missing)"))
                    ModpackPreview(cfg.name, cfg.description, cfg.minecraftVersion, cfg.loader, cfg.loaderVersion, z.entries.count { it.name.startsWith("files/") && !it.isDirectory }, "Eloa")
                }
            }
            tmp to preview
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    /** Creates the profile, unpacks bundled files and schedules installation. Returns profile id. */
    suspend fun install(packFile: File, preview: ModpackPreview): String = withContext(Dispatchers.IO) {
        if (preview.format == "Modrinth") {
            val p = profiles.create(ProfileConfig(name = preview.name.take(60), description = preview.summary.orEmpty(), minecraftVersion = preview.minecraftVersion,
                loader = preview.loader, loaderVersion = preview.loaderVersion))
            val keep = File(storage.importTemp, "mrpack-${p.id}.mrpack")
            packFile.renameTo(keep)
            InstallWorker.enqueue(context, p.id, keep.absolutePath)
            p.id
        } else {
            val cfg = SafeZip.open(packFile).use { z -> profiles.decode(z.readText(MANIFEST)!!) }
            val p = profiles.create(cfg)
            val game = storage.instance(p.gameDirName).gameDir
            val allowed = exportFolders + "saves"
            SafeZip.open(packFile).use { z ->
                z.extractTo(game, filter = { n -> n.startsWith("files/") && allowed.any { n.removePrefix("files/").let { r -> r == it || r.startsWith("$it/") } } }) { it.removePrefix("files/") }
            }
            packFile.delete()
            InstallWorker.enqueue(context, p.id)
            p.id
        }.also { logger.i("Modpacks", "Imported modpack ${preview.name}") }
    }

    companion object { const val MANIFEST = "eloapack.json" }
}
