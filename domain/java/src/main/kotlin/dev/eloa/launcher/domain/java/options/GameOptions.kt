package dev.eloa.launcher.domain.java.options

import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.version.ComparableVersion
import java.io.File

enum class GraphicsQuality { FAST, FANCY, FABULOUS }
enum class ParticleLevel { ALL, DECREASED, MINIMAL }
enum class CloudMode { OFF, FAST, FANCY }

/**
 * Settings Minecraft itself reads from `options.txt` in the game directory. Writing them there is
 * what makes them take effect: the game loads the file on start-up.
 */
data class GameOptions(
    val renderDistance: Int = 8,
    val simulationDistance: Int = 8,
    val graphics: GraphicsQuality = GraphicsQuality.FANCY,
    val vsync: Boolean = true,
    /** 260 means "unlimited" in Minecraft. */
    val maxFps: Int = 60,
    val mipmapLevels: Int = 4,
    val particles: ParticleLevel = ParticleLevel.ALL,
    val clouds: CloudMode = CloudMode.FANCY,
    val entityShadows: Boolean = true,
    val smoothLighting: Boolean = true,
    val fullscreen: Boolean = false,
    /** 0 = auto. */
    val guiScale: Int = 0,
    /** 0.0..1.0, Minecraft's default is 0.5. */
    val mouseSensitivity: Double = 0.5,
    val masterVolume: Double = 1.0,
    val musicVolume: Double = 1.0,
    val fov: Int = 70,
    val biomeBlendRadius: Int = 2,
) {
    fun clamped() = copy(
        renderDistance = renderDistance.coerceIn(2, 32),
        simulationDistance = simulationDistance.coerceIn(5, 32),
        maxFps = maxFps.coerceIn(10, 260),
        mipmapLevels = mipmapLevels.coerceIn(0, 4),
        guiScale = guiScale.coerceIn(0, 6),
        mouseSensitivity = mouseSensitivity.coerceIn(0.0, 1.0),
        masterVolume = masterVolume.coerceIn(0.0, 1.0),
        musicVolume = musicVolume.coerceIn(0.0, 1.0),
        fov = fov.coerceIn(30, 110),
        biomeBlendRadius = biomeBlendRadius.coerceIn(0, 7),
    )

    companion object {
        const val FILE_NAME = "options.txt"
    }
}

/**
 * Reads/merges/writes `options.txt`. Unknown keys (key bindings, mod options, resource pack order)
 * are always preserved; only the keys this launcher manages are replaced.
 */
object OptionsTxt {
    fun parse(text: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
        }
        return map
    }

    fun render(map: Map<String, String>): String = map.entries.joinToString("\n", postfix = "\n") { "${it.key}:${it.value}" }

    private fun isModern(mc: String, since: String): Boolean {
        // Snapshots (e.g. 24w14a), pre-releases and unknown ids are newer than the releases we gate on.
        if (!Regex("^1\\.\\d+(\\.\\d+)?").containsMatchIn(mc)) return !(mc.startsWith("a") || mc.startsWith("b") || mc.startsWith("c") || mc.startsWith("rd-") || mc.startsWith("inf-"))
        return ComparableVersion(Regex("^1\\.\\d+(\\.\\d+)?").find(mc)!!.value) >= ComparableVersion(since)
    }

    private fun d(v: Double) = if (v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()

    /** Key/value pairs for [o] in the format the given Minecraft version understands. */
    fun entriesFor(o: GameOptions, minecraftVersion: String): Map<String, String> {
        val x = o.clamped()
        val out = LinkedHashMap<String, String>()
        out["renderDistance"] = x.renderDistance.toString()
        if (isModern(minecraftVersion, "1.18")) out["simulationDistance"] = x.simulationDistance.toString()
        if (isModern(minecraftVersion, "1.16")) {
            out["graphicsMode"] = when (x.graphics) { GraphicsQuality.FAST -> "0"; GraphicsQuality.FANCY -> "1"; GraphicsQuality.FABULOUS -> "2" }
        } else {
            out["fancyGraphics"] = (x.graphics != GraphicsQuality.FAST).toString()
        }
        out["enableVsync"] = x.vsync.toString()
        out["maxFps"] = x.maxFps.toString()
        out["mipmapLevels"] = x.mipmapLevels.toString()
        out["particles"] = x.particles.ordinal.toString()
        val clouds = when (x.clouds) { CloudMode.OFF -> "false"; CloudMode.FAST -> "fast"; CloudMode.FANCY -> "true" }
        out["renderClouds"] = if (isModern(minecraftVersion, "1.13")) "\"$clouds\"" else clouds
        out["entityShadows"] = x.entityShadows.toString()
        out["ao"] = if (isModern(minecraftVersion, "1.19")) x.smoothLighting.toString() else if (x.smoothLighting) "2" else "0"
        out["fullscreen"] = x.fullscreen.toString()
        out["guiScale"] = x.guiScale.toString()
        out["mouseSensitivity"] = d(x.mouseSensitivity)
        out["soundCategory_master"] = d(x.masterVolume)
        out["soundCategory_music"] = d(x.musicVolume)
        // FOV is stored as (fov - 70) / 40 in -1..1.
        out["fov"] = d((x.fov - 70) / 40.0)
        if (isModern(minecraftVersion, "1.13")) out["biomeBlendRadius"] = x.biomeBlendRadius.toString()
        return out
    }

    /** Reads the managed values back from an existing file (unknown/missing values use defaults). */
    fun read(map: Map<String, String>): GameOptions {
        val def = GameOptions()
        fun int(k: String) = map[k]?.trim()?.toIntOrNull()
        fun dbl(k: String) = map[k]?.trim()?.toDoubleOrNull()
        fun bool(k: String) = map[k]?.trim()?.toBooleanStrictOrNull()
        val graphics = when (int("graphicsMode")) {
            0 -> GraphicsQuality.FAST; 1 -> GraphicsQuality.FANCY; 2 -> GraphicsQuality.FABULOUS
            else -> when (bool("fancyGraphics")) { false -> GraphicsQuality.FAST; true -> GraphicsQuality.FANCY; null -> def.graphics }
        }
        val clouds = when (map["renderClouds"]?.trim()?.trim('"')) { "false" -> CloudMode.OFF; "fast" -> CloudMode.FAST; "true" -> CloudMode.FANCY; else -> def.clouds }
        val ao = map["ao"]?.trim()?.let { it == "true" || (it.toIntOrNull() ?: 0) > 0 } ?: def.smoothLighting
        return GameOptions(
            renderDistance = int("renderDistance") ?: def.renderDistance,
            simulationDistance = int("simulationDistance") ?: def.simulationDistance,
            graphics = graphics,
            vsync = bool("enableVsync") ?: def.vsync,
            maxFps = int("maxFps") ?: def.maxFps,
            mipmapLevels = int("mipmapLevels") ?: def.mipmapLevels,
            particles = int("particles")?.let { ParticleLevel.entries.getOrNull(it) } ?: def.particles,
            clouds = clouds,
            entityShadows = bool("entityShadows") ?: def.entityShadows,
            smoothLighting = ao,
            fullscreen = bool("fullscreen") ?: def.fullscreen,
            guiScale = int("guiScale") ?: def.guiScale,
            mouseSensitivity = dbl("mouseSensitivity") ?: def.mouseSensitivity,
            masterVolume = dbl("soundCategory_master") ?: def.masterVolume,
            musicVolume = dbl("soundCategory_music") ?: def.musicVolume,
            fov = dbl("fov")?.let { (it * 40 + 70).toInt() } ?: def.fov,
            biomeBlendRadius = int("biomeBlendRadius") ?: def.biomeBlendRadius,
        ).clamped()
    }

    fun load(gameDir: File): GameOptions {
        val f = File(gameDir, GameOptions.FILE_NAME)
        return if (f.isFile) read(parse(f.readText())) else GameOptions()
    }

    /** Merges [o] into `gameDir/options.txt`, keeping every other key, and writes atomically. */
    fun apply(gameDir: File, o: GameOptions, minecraftVersion: String) {
        val f = File(gameDir, GameOptions.FILE_NAME)
        val map = if (f.isFile) parse(f.readText()) else LinkedHashMap()
        // Remove the legacy/modern twin of a key so the game never sees contradicting values.
        listOf("graphicsMode", "fancyGraphics").forEach { map.remove(it) }
        map.putAll(entriesFor(o, minecraftVersion))
        gameDir.mkdirs()
        FileOps.writeAtomically(f, render(map).toByteArray())
    }
}
