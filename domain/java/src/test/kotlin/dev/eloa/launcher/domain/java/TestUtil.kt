package dev.eloa.launcher.domain.java

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestUtil {
    fun resource(name: String): String = requireNotNull(javaClass.classLoader.getResource(name)) { name }.readText()

    fun jar(target: File, entries: Map<String, String>): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d.toByteArray()); z.closeEntry() } }
        return target
    }

    fun fabricMod(id: String, version: String = "1.0.0", depends: String = "", breaks: String = "", provides: String = "") =
        mapOf("fabric.mod.json" to """{"schemaVersion":1,"id":"$id","version":"$version","name":"${id.uppercase()}","authors":["A",{"name":"B"}],
            "depends":{$depends},"breaks":{$breaks},"provides":[$provides],"environment":"*"}""")

    fun forgeMod(id: String, version: String = "1.0.0", mc: String = "[1.20.1,1.21)", loader: String = "[47,)", extraDeps: String = "", file: String = "META-INF/mods.toml") = mapOf(file to """
        modLoader="javafml"
        loaderVersion="[47,)"
        license="MIT"
        [[mods]]
        modId="$id"
        version="$version"
        displayName="${id.uppercase()}"
        authors="X, Y"
        [[dependencies.$id]]
        modId="forge"
        mandatory=true
        versionRange="$loader"
        [[dependencies.$id]]
        modId="minecraft"
        mandatory=true
        versionRange="$mc"
        $extraDeps
    """.trimIndent())
}
