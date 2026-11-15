package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.toml.Toml
import dev.eloa.launcher.core.common.toml.TomlParseException
import org.junit.Assert.assertEquals
import org.junit.Test

class TomlTest {
    private val modsToml = """
        # Forge mods.toml
        modLoader="javafml" #mandatory
        loaderVersion="[47,)"
        license='MIT'
        [[mods]]
        modId="examplemod"
        version="1.0.0"
        displayName="Example Mod"
        description='''
        A multi-line
        description'''
        [[dependencies.examplemod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"
        [[dependencies.examplemod]]
            modId="minecraft"
            type="required"
            versionRange="[1.20.1,1.21)"
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    @Test fun `parses forge mods toml`() {
        val doc = Toml.parse(modsToml)
        assertEquals("javafml", doc["modLoader"])
        val mods = doc["mods"] as List<Map<String, Any?>>
        assertEquals("examplemod", mods[0]["modId"])
        assertEquals("A multi-line\ndescription", mods[0]["description"])
        val deps = (doc["dependencies"] as Map<String, Any?>)["examplemod"] as List<Map<String, Any?>>
        assertEquals(2, deps.size)
        assertEquals(true, deps[0]["mandatory"])
        assertEquals("[1.20.1,1.21)", deps[1]["versionRange"])
    }

    @Test fun `values and inline tables`() {
        val doc = Toml.parse("a = 1\nb = 2.5\nc = [1, 2, \"x\",]\nd = { e = true, f.g = \"h\" }\nk.l = \"m\"\nu = \"\\u0041\"\nn = 1_000")
        assertEquals(1L, doc["a"]); assertEquals(2.5, doc["b"]); assertEquals(listOf(1L, 2L, "x"), doc["c"])
        assertEquals(mapOf("e" to true, "f" to mapOf("g" to "h")), doc["d"])
        assertEquals(mapOf("l" to "m"), doc["k"]); assertEquals("A", doc["u"]); assertEquals(1000L, doc["n"])
    }

    @Test(expected = TomlParseException::class) fun `duplicate key fails`() { Toml.parse("a=1\na=2") }

    @Test(expected = TomlParseException::class) fun `unterminated string fails`() { Toml.parse("a=\"abc") }

    @Test(expected = TomlParseException::class) fun `garbage fails`() { Toml.parse("a = 1 b") }
}
