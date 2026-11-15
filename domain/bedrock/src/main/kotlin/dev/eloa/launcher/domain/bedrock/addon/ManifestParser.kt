package dev.eloa.launcher.domain.bedrock.addon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ManifestException(message: String) : IllegalArgumentException(message)

/**
 * Parses Bedrock pack manifest.json. Tolerates comments and trailing commas (common in
 * community packs) and both version formats: `[1, 0, 0]` (format 1/2) and `"1.0.0"` (format 3).
 */
object ManifestParser {
    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; allowComments = true; allowTrailingComma = true }

    fun isValidUuid(s: String?) = s != null && UUID_REGEX.matches(s)

    /** @param lang optional en_US.lang content used to resolve localized names like "pack.name". */
    fun parse(text: String, lang: String? = null): PackManifest {
        val root = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw ManifestException("manifest.json is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val format = (root["format_version"] as? JsonPrimitive)?.intOrNull
            ?: (root["format_version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        val header = root["header"] as? JsonObject ?: throw ManifestException("manifest.json has no header")
        val uuid = header.str("uuid") ?: throw ManifestException("header.uuid is missing")
        val version = header["version"]?.let(::versionString) ?: throw ManifestException("header.version is missing")
        val modules = (root["modules"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val moduleTypes = modules.mapNotNull { it.str("type")?.lowercase() }
        val type = when {
            moduleTypes.any { it == "resources" } && moduleTypes.none { it == "data" || it == "script" } -> PackType.RESOURCE
            moduleTypes.any { it == "data" || it == "script" || it == "javascript" || it == "client_data" } -> PackType.BEHAVIOR
            moduleTypes.any { it == "skin_pack" } -> PackType.SKIN
            moduleTypes.any { it == "world_template" } -> PackType.WORLD_TEMPLATE
            else -> PackType.UNKNOWN
        }
        val deps = (root["dependencies"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PackDependency(o.str("uuid"), o.str("module_name"), o["version"]?.let(::versionString) ?: "*")
        }.orEmpty()
        val authors = (root["metadata"] as? JsonObject)?.get("authors")?.let { a ->
            (a as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.orEmpty()
        val langMap = lang?.let(::parseLang).orEmpty()
        fun localize(s: String?): String? = s?.let { langMap[it] ?: it }
        return PackManifest(
            formatVersion = format,
            name = localize(header.str("name"))?.let(::stripFormatting)?.takeIf { it.isNotBlank() } ?: "Unnamed pack",
            description = localize(header.str("description"))?.let(::stripFormatting).orEmpty(),
            uuid = uuid.lowercase(),
            version = version,
            minEngineVersion = header["min_engine_version"]?.let(::versionString),
            type = type,
            moduleTypes = moduleTypes,
            moduleUuids = modules.mapNotNull { it.str("uuid")?.lowercase() },
            dependencies = deps,
            authors = authors,
        )
    }

    /** Structural checks producing human-readable problems. */
    fun validate(m: PackManifest): List<String> {
        val problems = ArrayList<String>()
        if (!isValidUuid(m.uuid)) problems += "Header UUID '${m.uuid}' is not a valid UUID."
        m.moduleUuids.forEach { if (!isValidUuid(it)) problems += "Module UUID '$it' is not a valid UUID." }
        if (m.moduleUuids.contains(m.uuid)) problems += "Module UUID duplicates the header UUID."
        if (m.moduleUuids.size != m.moduleUuids.toSet().size) problems += "Duplicate module UUIDs."
        if (m.moduleTypes.isEmpty() && m.type != PackType.UNKNOWN) problems += "No modules declared."
        if (m.type == PackType.UNKNOWN) problems += "Unknown pack type (modules: ${m.moduleTypes.joinToString().ifEmpty { "none" }})."
        m.dependencies.forEach { d ->
            if (d.uuid == null && d.moduleName == null) problems += "Dependency without uuid or module_name."
            if (d.uuid != null && !isValidUuid(d.uuid)) problems += "Dependency UUID '${d.uuid}' is not valid."
        }
        if (m.type != PackType.SKIN && m.minEngineVersion == null && m.formatVersion >= 2) {
            problems += "min_engine_version is missing."
        }
        return problems
    }

    private fun versionString(el: JsonElement): String? = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.takeIf { it.isNotEmpty() }?.joinToString(".")
        is JsonPrimitive -> el.contentOrNull
        else -> null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun stripFormatting(s: String) = s.replace(Regex("§."), "").trim()

    fun parseLang(text: String): Map<String, String> = text.lineSequence()
        .map { it.substringBefore("\t#").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}
