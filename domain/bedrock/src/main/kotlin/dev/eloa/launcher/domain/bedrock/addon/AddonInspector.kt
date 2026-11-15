package dev.eloa.launcher.domain.bedrock.addon

import dev.eloa.launcher.core.common.archive.ArchiveLimitException
import dev.eloa.launcher.core.common.archive.ArchiveLimits
import dev.eloa.launcher.core.common.archive.CorruptArchiveException
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.domain.bedrock.apk.Finding
import dev.eloa.launcher.domain.bedrock.apk.Severity
import dev.eloa.launcher.domain.bedrock.nbt.Nbt
import dev.eloa.launcher.domain.bedrock.nbt.NbtException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Analyses Bedrock content files (.mcpack, .mcaddon, .mcworld, .mctemplate, .mcstructure, .zip)
 * without executing anything. Nested .mcpack files inside .mcaddon are inspected in memory
 * (bounded by [ArchiveLimits.MAX_METADATA_BYTES] per manifest).
 */
class AddonInspector(private val limits: ArchiveLimits = ArchiveLimits.DEFAULT) {

    fun kindOf(file: File): AddonKind? = when (file.extension.lowercase()) {
        "mcpack" -> AddonKind.MCPACK
        "mcaddon" -> AddonKind.MCADDON
        "mcworld" -> AddonKind.MCWORLD
        "mctemplate" -> AddonKind.MCTEMPLATE
        "mcstructure" -> AddonKind.MCSTRUCTURE
        "zip" -> AddonKind.ZIP
        else -> null
    }

    fun inspect(file: File): AddonAnalysis {
        val kind = kindOf(file) ?: return failure(file, AddonKind.ZIP, "UNSUPPORTED", "Unsupported file type .${file.extension}")
        if (kind == AddonKind.MCSTRUCTURE) return inspectStructure(file)
        if (!SafeZip.isZip(file)) return failure(file, kind, "NOT_ZIP", "File is not a valid archive")
        return try {
            SafeZip.open(file, limits).use { zip -> inspectZip(file.name, kind, zip) }
        } catch (e: CorruptArchiveException) {
            failure(file, kind, "CORRUPT", "Archive is damaged: ${e.message}")
        } catch (e: ArchiveLimitException) {
            failure(file, kind, "LIMITS", "Archive rejected by safety limits: ${e.message}")
        } catch (e: IOException) {
            failure(file, kind, "IO", "Cannot read file: ${e.message}")
        }
    }

    private fun inspectZip(name: String, kind: AddonKind, zip: SafeZip): AddonAnalysis {
        val findings = ArrayList<Finding>()
        val packs = ArrayList<PackEntry>()
        val names = zip.entries.map { it.name }

        // World detection (level.dat at root or one folder deep)
        val levelDat = names.firstOrNull { it == "level.dat" || (it.count { c -> c == '/' } == 1 && it.endsWith("/level.dat")) }
        var world: WorldInfo? = null
        if (levelDat != null) {
            val root = levelDat.removeSuffix("level.dat")
            val levelName = zip.readText(root + "levelname.txt")?.trim()?.takeIf { it.isNotEmpty() }
            val nbt = try { zip.readBytes(levelDat)?.let(::readLevelDat) } catch (e: NbtException) {
                findings += Finding(Severity.WARNING, "LEVEL_DAT", "level.dat could not be parsed: ${e.message}"); null
            }
            val embedded = names.count { it.startsWith(root + "behavior_packs/") && it.endsWith("/manifest.json") } +
                names.count { it.startsWith(root + "resource_packs/") && it.endsWith("/manifest.json") }
            world = WorldInfo(
                levelName = levelName ?: (nbt?.get("LevelName") as? String) ?: name.substringBeforeLast('.'),
                gameType = (nbt?.get("GameType") as? Int),
                lastPlayed = (nbt?.get("LastPlayed") as? Long),
                lastOpenedWithVersion = (nbt?.get("lastOpenedWithVersion") as? List<*>)?.mapNotNull { it as? Int }?.joinToString("."),
                embeddedPacks = embedded,
            )
            if (!names.any { it.startsWith(root + "db/") }) {
                findings += Finding(Severity.WARNING, "NO_DB", "World has no db/ folder — it may be empty or damaged.")
            }
        }

        // Packs directly in the archive
        for (manifestPath in names.filter { it == "manifest.json" || it.endsWith("/manifest.json") }) {
            val root = manifestPath.removeSuffix("manifest.json")
            if (world != null && (root.contains("behavior_packs/") || root.contains("resource_packs/"))) continue
            if (root.count { it == '/' } > 3) continue
            parsePack(root, zip.readText(manifestPath), zip.readText(root + "texts/en_US.lang"), root + "pack_icon.png" in names)
                .let { (p, f) -> p?.let(packs::add); findings += f }
        }
        // Nested .mcpack/.zip files (common in .mcaddon)
        for (nested in names.filter { it.lowercase().endsWith(".mcpack") || (kind == AddonKind.MCADDON && it.lowercase().endsWith(".zip")) }) {
            zip.openStream(nested) { s -> readNestedPack(nested, s.readNBytesBounded(), findings) }?.let(packs::addAll)
        }

        when (kind) {
            AddonKind.MCWORLD -> if (world == null) findings += Finding(Severity.ERROR, "NO_WORLD", "No level.dat found — not a Bedrock world.")
            AddonKind.MCTEMPLATE -> if (world == null && packs.none { it.manifest.type == PackType.WORLD_TEMPLATE }) {
                findings += Finding(Severity.ERROR, "NO_TEMPLATE", "No world template found.")
            }
            else -> if (packs.isEmpty() && world == null) findings += Finding(Severity.ERROR, "NO_PACKS", "No manifest.json found — not a Bedrock pack.")
        }
        findings += crossCheck(packs)
        return AddonAnalysis(name, kind, packs, world, null, findings)
    }

    private fun java.io.InputStream.readNBytesBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf); if (n < 0) break
            total += n
            if (total > limits.maxEntrySize.coerceAtMost(512L * 1024 * 1024)) throw ArchiveLimitException("Nested pack too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readNestedPack(entryName: String, bytes: ByteArray, findings: MutableList<Finding>): List<PackEntry> {
        val files = HashMap<String, ByteArray>()
        val wanted = { n: String -> n.endsWith("manifest.json") || n.endsWith("texts/en_US.lang") }
        val allNames = ArrayList<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    allNames += e.name
                    if (allNames.size > limits.maxEntries) throw ArchiveLimitException("Too many entries in $entryName")
                    if (!e.isDirectory && wanted(e.name)) {
                        val data = zis.readBounded(ArchiveLimits.MAX_METADATA_BYTES)
                        files[e.name] = data
                    }
                    e = zis.nextEntry
                }
            }
        } catch (ex: java.util.zip.ZipException) {
            findings += Finding(Severity.ERROR, "NESTED_CORRUPT", "Nested pack $entryName is damaged: ${ex.message}")
            return emptyList()
        }
        val result = ArrayList<PackEntry>()
        for ((path, data) in files.filterKeys { it.endsWith("manifest.json") }) {
            val root = path.removeSuffix("manifest.json")
            if (root.count { it == '/' } > 2) continue
            parsePack("$entryName!/$root", data.toString(Charsets.UTF_8).removePrefix("﻿"),
                files[root + "texts/en_US.lang"]?.toString(Charsets.UTF_8), (root + "pack_icon.png") in allNames)
                .let { (p, f) -> p?.let(result::add); findings += f }
        }
        if (result.isEmpty()) findings += Finding(Severity.WARNING, "NESTED_EMPTY", "Nested file $entryName contains no pack manifest.")
        return result
    }

    private fun ZipInputStream.readBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf); if (n < 0) break
            total += n
            if (total > max) throw ArchiveLimitException("Metadata file too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parsePack(root: String, manifestText: String?, lang: String?, hasIcon: Boolean): Pair<PackEntry?, List<Finding>> {
        if (manifestText == null) return null to emptyList()
        return try {
            val m = ManifestParser.parse(manifestText, lang)
            val f = ManifestParser.validate(m).map { Finding(if (it.contains("UUID")) Severity.ERROR else Severity.WARNING, "MANIFEST", "${m.name}: $it") }
            PackEntry(root, m, hasIcon) to f
        } catch (e: ManifestException) {
            null to listOf(Finding(Severity.ERROR, "MANIFEST_INVALID", "${root.ifEmpty { "/" }}: ${e.message}"))
        }
    }

    private fun crossCheck(packs: List<PackEntry>): List<Finding> {
        val out = ArrayList<Finding>()
        packs.groupBy { it.manifest.uuid }.filter { it.value.size > 1 }.forEach { (uuid, list) ->
            out += Finding(Severity.ERROR, "DUP_UUID", "Packs ${list.joinToString { it.manifest.name }} share UUID $uuid.")
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun readLevelDat(bytes: ByteArray): Map<String, Any?> {
        // Bedrock: 8-byte header (storage version, payload length) followed by little-endian NBT.
        if (bytes.size < 9) throw NbtException("level.dat too small")
        return Nbt.readLittleEndian(ByteArrayInputStream(bytes, 8, bytes.size - 8)).second
    }

    private fun inspectStructure(file: File): AddonAnalysis {
        if (file.length() > 64L * 1024 * 1024) return failure(file, AddonKind.MCSTRUCTURE, "TOO_LARGE", "Structure file exceeds 64 MB")
        return try {
            val raw = file.readBytes()
            val stream = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(raw)) else ByteArrayInputStream(raw)
            val (_, root) = Nbt.readLittleEndian(stream)
            val size = (root["size"] as? List<*>)?.mapNotNull { it as? Int }
            if (size == null || size.size != 3) {
                failure(file, AddonKind.MCSTRUCTURE, "NO_SIZE", "Structure has no valid 'size' tag")
            } else {
                val findings = if (size.any { it <= 0 || it > 1024 }) listOf(Finding(Severity.WARNING, "SIZE", "Unusual structure size ${size.joinToString("×")}")) else emptyList()
                AddonAnalysis(file.name, AddonKind.MCSTRUCTURE, emptyList(), null,
                    StructureInfo(size[0], size[1], size[2], root["format_version"] as? Int), findings)
            }
        } catch (e: NbtException) {
            failure(file, AddonKind.MCSTRUCTURE, "BAD_NBT", "Not a valid .mcstructure: ${e.message}")
        } catch (e: IOException) {
            failure(file, AddonKind.MCSTRUCTURE, "IO", "Cannot read structure: ${e.message}")
        }
    }

    private fun failure(file: File, kind: AddonKind, code: String, msg: String) =
        AddonAnalysis(file.name, kind, emptyList(), null, null, listOf(Finding(Severity.ERROR, code, msg)))
}
