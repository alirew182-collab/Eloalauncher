package dev.eloa.launcher.domain.java.content

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.version.ComparableVersion
import dev.eloa.launcher.domain.java.net.LauncherJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Resource pack `pack_format` values per Minecraft release (from the Minecraft Wiki).
 * Snapshots and versions outside the table are reported as "unknown" rather than guessed.
 */
object PackFormats {
    private data class Row(val format: Int, val from: String, val to: String)

    private val RESOURCE = listOf(
        Row(1, "1.6.1", "1.8.9"), Row(2, "1.9", "1.10.2"), Row(3, "1.11", "1.12.2"), Row(4, "1.13", "1.14.4"),
        Row(5, "1.15", "1.16.1"), Row(6, "1.16.2", "1.16.5"), Row(7, "1.17", "1.17.1"), Row(8, "1.18", "1.18.2"),
        Row(9, "1.19", "1.19.2"), Row(12, "1.19.3", "1.19.3"), Row(13, "1.19.4", "1.19.4"), Row(15, "1.20", "1.20.1"),
        Row(18, "1.20.2", "1.20.2"), Row(22, "1.20.3", "1.20.4"), Row(32, "1.20.5", "1.20.6"), Row(34, "1.21", "1.21.1"),
        Row(42, "1.21.2", "1.21.3"), Row(46, "1.21.4", "1.21.4"),
    )

    /** Expected resource pack format for a release, or null when unknown. */
    fun resourceFormatFor(mcVersion: String): Int? {
        if (!Regex("^\\d+\\.\\d+(\\.\\d+)?$").matches(mcVersion)) return null
        return RESOURCE.firstOrNull { ComparableVersion.compare(mcVersion, it.from) >= 0 && ComparableVersion.compare(mcVersion, it.to) <= 0 }?.format
    }

    data class PackMeta(val format: Int?, val description: String, val supportedMin: Int?, val supportedMax: Int?)

    fun readPackMeta(zip: SafeZip): PackMeta? {
        val text = zip.readText("pack.mcmeta") ?: return null
        val pack = runCatching { (LauncherJson.parseToJsonElement(text) as JsonObject)["pack"] as? JsonObject }.getOrNull() ?: return PackMeta(null, "", null, null)
        val desc = when (val d = pack["description"]) {
            is JsonPrimitive -> d.contentOrNull.orEmpty()
            is JsonObject -> (d["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> d?.toString().orEmpty()
        }.replace(Regex("§."), "")
        var min: Int? = null; var max: Int? = null
        when (val s = pack["supported_formats"]) {
            is JsonPrimitive -> { min = s.intOrNull; max = s.intOrNull }
            is kotlinx.serialization.json.JsonArray -> { min = (s.getOrNull(0) as? JsonPrimitive)?.intOrNull; max = (s.getOrNull(1) as? JsonPrimitive)?.intOrNull }
            is JsonObject -> { min = (s["min_inclusive"] as? JsonPrimitive)?.intOrNull; max = (s["max_inclusive"] as? JsonPrimitive)?.intOrNull }
            else -> Unit
        }
        return PackMeta((pack["pack_format"] as? JsonPrimitive)?.intOrNull, desc, min, max)
    }

    enum class Compat { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

    fun compatibility(meta: PackMeta, mcVersion: String): Compat {
        val expected = resourceFormatFor(mcVersion) ?: return Compat.UNKNOWN
        val f = meta.format ?: return Compat.UNKNOWN
        if (meta.supportedMin != null && meta.supportedMax != null) {
            return if (expected in meta.supportedMin..meta.supportedMax) Compat.COMPATIBLE else Compat.INCOMPATIBLE
        }
        return if (f == expected) Compat.COMPATIBLE else Compat.INCOMPATIBLE
    }
}

enum class JavaContentType(val folder: String, val extensions: Set<String>) {
    RESOURCE_PACKS("resourcepacks", setOf("zip")),
    SHADER_PACKS("shaderpacks", setOf("zip")),
    DATAPACKS("datapacks", setOf("zip")),
    WORLDS("saves", setOf("zip")),
    CONFIGS("config", setOf("json", "json5", "toml", "cfg", "properties", "txt", "yml", "yaml", "snbt")),
}

data class ContentValidation(val ok: Boolean, val message: String?)

/** Structural validation of content archives before they are imported. */
object ContentValidator {
    fun validate(type: JavaContentType, file: File, displayName: String): ContentValidation {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in type.extensions) return ContentValidation(false, "Unsupported file type .$ext for ${type.folder}")
        if (type == JavaContentType.CONFIGS) {
            if (file.length() > 2 * 1024 * 1024) return ContentValidation(false, "Config file larger than 2 MB")
            val head = file.inputStream().use { s -> ByteArray(minOf(4096, file.length().toInt())).also { s.read(it) } }
            if (head.any { it == 0.toByte() }) return ContentValidation(false, "Config file looks binary")
            return ContentValidation(true, null)
        }
        if (!SafeZip.isZip(file)) return ContentValidation(false, "Not a ZIP archive")
        return try {
            SafeZip.open(file).use { z ->
                val names = z.entries.map { it.name }
                when (type) {
                    JavaContentType.RESOURCE_PACKS -> if ("pack.mcmeta" !in names) ContentValidation(false, "pack.mcmeta missing — not a resource pack") else ContentValidation(true, null)
                    JavaContentType.DATAPACKS -> when {
                        "pack.mcmeta" !in names -> ContentValidation(false, "pack.mcmeta missing — not a datapack")
                        names.none { it.startsWith("data/") } -> ContentValidation(false, "data/ folder missing — not a datapack")
                        else -> ContentValidation(true, null)
                    }
                    JavaContentType.SHADER_PACKS -> if (names.none { it.startsWith("shaders/") || it.contains("/shaders/") }) ContentValidation(false, "shaders/ folder missing — not a shader pack") else ContentValidation(true, null)
                    JavaContentType.WORLDS -> if (names.none { it == "level.dat" || it.endsWith("/level.dat") && it.count { c -> c == '/' } == 1 }) ContentValidation(false, "level.dat missing — not a Java world") else ContentValidation(true, null)
                    JavaContentType.CONFIGS -> ContentValidation(true, null)
                }
            }
        } catch (e: java.io.IOException) {
            ContentValidation(false, "Archive is damaged: ${e.message}")
        }
    }
}
