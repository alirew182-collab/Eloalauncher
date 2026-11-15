package dev.eloa.launcher.domain.java.install

import dev.eloa.launcher.core.common.io.PathSafety
import java.io.File

/**
 * Shared Minecraft data layout (versions, libraries, assets are shared between profiles to save
 * storage; every profile has its own independent game directory).
 */
class GameDirectories(val root: File) {
    val versions = File(root, "versions")
    val libraries = File(root, "libraries")
    val assets = File(root, "assets")
    val assetIndexes = File(assets, "indexes")
    val assetObjects = File(assets, "objects")
    val cache = File(root, "cache")
    val runtimes = File(root, "runtimes")

    fun versionDir(id: String) = PathSafety.resolveInside(versions, id)
    fun versionJson(id: String) = File(versionDir(id), "$id.json")
    fun clientJar(id: String) = File(versionDir(id), "$id.jar")
    fun nativesDir(id: String) = File(versionDir(id), "natives")
    fun library(path: String) = PathSafety.resolveInside(libraries, path)
    fun assetIndex(id: String) = PathSafety.resolveInside(assetIndexes, "$id.json")
    fun assetObject(hash: String): File {
        require(hash.length == 40 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid asset hash" }
        return File(File(assetObjects, hash.take(2)), hash)
    }
    fun legacyAssets(id: String) = PathSafety.resolveInside(File(assets, "virtual"), id)

    fun installedVersionIds(): List<String> =
        versions.listFiles()?.filter { File(it, "${it.name}.json").isFile }?.map { it.name }?.sorted().orEmpty()
}

/** Per-profile game directory with the standard sub-folders. */
class InstanceDirectories(val gameDir: File) {
    val mods = File(gameDir, "mods")
    val config = File(gameDir, "config")
    val resourcePacks = File(gameDir, "resourcepacks")
    val shaderPacks = File(gameDir, "shaderpacks")
    val saves = File(gameDir, "saves")
    val screenshots = File(gameDir, "screenshots")
    val logs = File(gameDir, "logs")
    val backups = File(gameDir, ".eloa-backups")

    fun ensure() = listOf(gameDir, mods, config, resourcePacks, shaderPacks, saves).forEach { it.mkdirs() }

    fun datapacks(world: String) = File(PathSafety.resolveInside(saves, world), "datapacks")
}
