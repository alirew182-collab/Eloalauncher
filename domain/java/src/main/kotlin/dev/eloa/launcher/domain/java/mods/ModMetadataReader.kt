package dev.eloa.launcher.domain.java.mods

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.toml.Toml
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.net.LauncherJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

class ModMetadataException(message: String) : IOException(message)

/**
 * Reads mod metadata from jar files without loading any classes:
 * fabric.mod.json, quilt.mod.json, META-INF/neoforge.mods.toml, META-INF/mods.toml, mcmod.info.
 * A jar may carry several formats (multi-loader mods); all are returned.
 */
object ModMetadataReader {
    private val lenient = Json(LauncherJson) { allowComments = true; allowTrailingComma = true }

    fun read(jar: File): List<ModMetadata> = SafeZip.open(jar).use { zip ->
        val out = ArrayList<ModMetadata>()
        zip.readText("quilt.mod.json")?.let { out += parseQuilt(it) }
        zip.readText("fabric.mod.json")?.let { out += parseFabric(it) }
        val manifestVersion = zip.readText("META-INF/MANIFEST.MF")?.let(::implementationVersion)
        zip.readText("META-INF/neoforge.mods.toml")?.let { out += parseModsToml(it, ModLoaderType.NEOFORGE, manifestVersion) }
        zip.readText("META-INF/mods.toml")?.let { out += parseModsToml(it, ModLoaderType.FORGE, manifestVersion) }
        if (out.isEmpty()) zip.readText("mcmod.info")?.let { out += parseMcmodInfo(it) }
        out
    }

    private fun implementationVersion(mf: String): String? =
        mf.lineSequence().firstOrNull { it.startsWith("Implementation-Version:") }?.substringAfter(':')?.trim()

    // ---- Fabric -------------------------------------------------------------------------
    fun parseFabric(text: String): ModMetadata {
        val o = parseObject(text, "fabric.mod.json")
        val id = o.str("id") ?: throw ModMetadataException("fabric.mod.json: missing id")
        validateId(id)
        val deps = ArrayList<ModDependency>()
        fun section(key: String, kind: DependencyKind) {
            (o[key] as? JsonObject)?.forEach { (depId, v) -> deps += ModDependency(depId, stringOrList(v), kind, RangeSyntax.FABRIC) }
        }
        section("depends", DependencyKind.REQUIRED)
        section("recommends", DependencyKind.OPTIONAL)
        section("suggests", DependencyKind.OPTIONAL)
        section("breaks", DependencyKind.INCOMPATIBLE)
        return ModMetadata(
            id = id,
            name = o.str("name") ?: id,
            version = o.str("version") ?: "0",
            description = o.str("description").orEmpty(),
            authors = people(o["authors"]),
            format = ModLoaderType.FABRIC,
            dependencies = deps,
            provides = (o["provides"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            environment = o.str("environment") ?: "*",
            nestedJars = (o["jars"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("file") }.orEmpty(),
            license = (o["license"] as? JsonPrimitive)?.contentOrNull ?: (o["license"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    // ---- Quilt --------------------------------------------------------------------------
    fun parseQuilt(text: String): ModMetadata {
        val root = parseObject(text, "quilt.mod.json")
        val ql = root["quilt_loader"] as? JsonObject ?: throw ModMetadataException("quilt.mod.json: missing quilt_loader")
        val id = ql.str("id") ?: throw ModMetadataException("quilt.mod.json: missing id")
        validateId(id)
        val meta = ql["metadata"] as? JsonObject
        val deps = ArrayList<ModDependency>()
        fun parse(arr: JsonElement?, defaultKind: DependencyKind) {
            (arr as? JsonArray)?.forEach { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull?.let { deps += ModDependency(it, listOf("*"), defaultKind, RangeSyntax.FABRIC) }
                    is JsonObject -> {
                        val depId = el.str("id") ?: return@forEach
                        val optional = (el["optional"] as? JsonPrimitive)?.booleanOrNull == true
                        val kind = if (defaultKind == DependencyKind.REQUIRED && optional) DependencyKind.OPTIONAL else defaultKind
                        deps += ModDependency(depId.substringAfter(':'), stringOrList(el["versions"]).ifEmpty { listOf("*") }, kind, RangeSyntax.FABRIC)
                    }
                    else -> Unit
                }
            }
        }
        parse(ql["depends"], DependencyKind.REQUIRED)
        parse(ql["breaks"], DependencyKind.INCOMPATIBLE)
        val contributors = (meta?.get("contributors") as? JsonObject)?.keys?.toList().orEmpty()
        return ModMetadata(
            id = id,
            name = meta?.str("name") ?: id,
            version = ql.str("version") ?: "0",
            description = meta?.str("description").orEmpty(),
            authors = contributors,
            format = ModLoaderType.QUILT,
            dependencies = deps,
            provides = (ql["provides"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.str("id") }.orEmpty(),
            environment = (root["minecraft"] as? JsonObject)?.str("environment") ?: "*",
            nestedJars = (ql["jars"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            license = (meta?.get("license") as? JsonPrimitive)?.contentOrNull,
        )
    }

    // ---- Forge / NeoForge ---------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    fun parseModsToml(text: String, format: ModLoaderType, implementationVersion: String? = null): List<ModMetadata> {
        val doc = try { Toml.parse(text) } catch (e: IllegalArgumentException) {
            throw ModMetadataException("mods.toml: ${e.message}")
        }
        val mods = doc["mods"] as? List<Map<String, Any?>> ?: throw ModMetadataException("mods.toml: no [[mods]] section")
        val depTable = doc["dependencies"] as? Map<String, Any?> ?: emptyMap()
        val license = doc["license"] as? String
        return mods.map { m ->
            val id = m["modId"] as? String ?: throw ModMetadataException("mods.toml: modId missing")
            validateId(id)
            var version = (m["version"] as? String) ?: "0"
            if (version.contains("\${file.jarVersion}")) version = implementationVersion ?: version
            val deps = (depTable[id] as? List<Map<String, Any?>>).orEmpty().mapNotNull { d ->
                val depId = d["modId"] as? String ?: return@mapNotNull null
                val kind = when {
                    d.containsKey("type") -> when ((d["type"] as? String)?.lowercase()) {
                        "required" -> DependencyKind.REQUIRED
                        "incompatible" -> DependencyKind.INCOMPATIBLE
                        else -> DependencyKind.OPTIONAL // optional, discouraged
                    }
                    d["mandatory"] == true -> DependencyKind.REQUIRED
                    else -> DependencyKind.OPTIONAL
                }
                ModDependency(depId, listOf((d["versionRange"] as? String) ?: "*"), kind, RangeSyntax.MAVEN)
            }
            ModMetadata(
                id = id,
                name = (m["displayName"] as? String) ?: id,
                version = version,
                description = ((m["description"] as? String) ?: "").trim(),
                authors = ((m["authors"] as? String) ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                format = format,
                dependencies = deps,
                provides = emptyList(),
                environment = "*",
                nestedJars = emptyList(),
                license = license,
            )
        }
    }

    // ---- Legacy Forge (≤ 1.12) ------------------------------------------------------------
    fun parseMcmodInfo(text: String): List<ModMetadata> {
        val el = try { lenient.parseToJsonElement(text) } catch (e: Exception) { throw ModMetadataException("mcmod.info is not valid JSON") }
        val list = when (el) {
            is JsonArray -> el
            is JsonObject -> el["modList"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.str("modid") ?: return@mapNotNull null
            val mc = o.str("mcversion")
            ModMetadata(
                id = id, name = o.str("name") ?: id, version = o.str("version") ?: "0",
                description = o.str("description").orEmpty(),
                authors = (o["authorList"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                format = ModLoaderType.FORGE,
                dependencies = if (mc != null && !mc.contains("\${")) listOf(ModDependency("minecraft", listOf("[$mc]"), DependencyKind.REQUIRED, RangeSyntax.MAVEN)) else emptyList(),
                provides = emptyList(), environment = "*", nestedJars = emptyList(), license = null,
            )
        }
    }

    private fun parseObject(text: String, what: String): JsonObject = try {
        lenient.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject ?: throw ModMetadataException("$what is not an object")
    } catch (e: ModMetadataException) { throw e } catch (e: Exception) {
        throw ModMetadataException("$what is not valid JSON")
    }

    private fun validateId(id: String) {
        if (id.isBlank() || id.length > 64) throw ModMetadataException("Invalid mod id '$id'")
    }

    private fun stringOrList(el: JsonElement?): List<String> = when (el) {
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun people(el: JsonElement?): List<String> = (el as? JsonArray)?.mapNotNull {
        when (it) { is JsonPrimitive -> it.contentOrNull; is JsonObject -> it.str("name"); else -> null }
    }.orEmpty()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
