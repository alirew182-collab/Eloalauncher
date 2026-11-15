package dev.eloa.launcher.domain.java

import dev.eloa.launcher.domain.java.install.GameDirectories
import dev.eloa.launcher.domain.java.install.LibraryPlanner
import dev.eloa.launcher.domain.java.meta.MavenCoordinate
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionChannel
import dev.eloa.launcher.domain.java.meta.VersionJson
import dev.eloa.launcher.domain.java.meta.VersionManifest
import dev.eloa.launcher.domain.java.meta.VersionResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `parses version manifest and channels`() {
        val m = VersionManifest.parse(TestUtil.resource("version_manifest_v2.json"))
        assertEquals("1.21.4", m.latest.release)
        assertEquals(4, m.versions.size)
        assertEquals(VersionChannel.SNAPSHOT, m.versions[0].channel)
        assertEquals(VersionChannel.OLD_BETA, m.versions[3].channel)
    }

    @Test fun `parses version json`() {
        val v = VersionJson.parse(TestUtil.resource("1.20.1.json"))
        assertEquals(17, v.javaVersion?.majorVersion)
        assertEquals(5, v.libraries.size)
        assertEquals("5", v.assetIndex?.id)
    }

    @Test fun `rules select linux libraries only`() {
        val v = VersionJson.parse(TestUtil.resource("1.20.1.json"))
        val libs = LibraryPlanner.resolve(v, GameDirectories(tmp.root), RuleEnvironment.forAbi("arm64-v8a"))
        val names = libs.map { it.library.name }
        assertTrue("org.lwjgl:lwjgl:3.3.1:natives-linux" in names)
        assertFalse(names.any { it.contains("windows") || it.contains("objc") })
        assertTrue(libs.all { it.file.path.startsWith(tmp.root.path) })
    }

    @Test fun `rule evaluation`() {
        val env = RuleEnvironment.forAbi("arm64-v8a", mapOf("is_demo_user" to false))
        assertTrue(env.allows(null))
        assertFalse(env.allows(listOf(dev.eloa.launcher.domain.java.meta.Rule("allow", dev.eloa.launcher.domain.java.meta.OsRule(name = "osx")))))
        assertFalse(env.allows(listOf(
            dev.eloa.launcher.domain.java.meta.Rule("allow"),
            dev.eloa.launcher.domain.java.meta.Rule("disallow", dev.eloa.launcher.domain.java.meta.OsRule(name = "linux")),
        )))
        assertTrue(env.allows(listOf(dev.eloa.launcher.domain.java.meta.Rule("allow", dev.eloa.launcher.domain.java.meta.OsRule(arch = "arm64")))))
        assertFalse(env.allows(listOf(dev.eloa.launcher.domain.java.meta.Rule("allow", features = mapOf("is_demo_user" to true)))))
    }

    @Test fun `maven coordinates`() {
        val c = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.1:natives-linux")
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar", c.path)
        assertEquals("de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1.zip", MavenCoordinate.parse("de.oceanlabs.mcp:mcp_config:1.20.1@zip").path)
        var thrown = false
        try { MavenCoordinate.parse("a:..:1") } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `fabric profile merges onto vanilla`() = runTest {
        val vanilla = VersionJson.parse(TestUtil.resource("1.20.1.json"))
        val fabric = VersionJson.parse(TestUtil.resource("fabric-profile.json"))
        val merged = VersionResolver.resolve(fabric) { id -> assertEquals("1.20.1", id); vanilla }
        assertNull(merged.inheritsFrom)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", merged.mainClass)
        assertEquals("fabric-loader-0.15.11-1.20.1", merged.id)
        // com.mojang:logging from child (1.2.0) replaces parent (1.1.1)
        assertEquals(listOf("com.mojang:logging:1.2.0"), merged.libraries.map { it.name }.filter { it.startsWith("com.mojang:logging") })
        assertEquals(17, merged.javaVersion?.majorVersion)
        assertTrue(merged.arguments!!.jvm.size > vanilla.arguments!!.jvm.size)
        val libs = LibraryPlanner.resolve(merged, GameDirectories(tmp.root), RuleEnvironment.forAbi("arm64-v8a"))
        val loader = libs.first { it.library.name.startsWith("net.fabricmc:fabric-loader") }
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar", loader.download!!.url)
    }

    @Test(expected = IllegalStateException::class) fun `cyclic inheritance detected`() = runTest {
        val a = VersionJson(id = "a", inheritsFrom = "b")
        VersionResolver.resolve(a) { id -> VersionJson(id = id, inheritsFrom = if (id == "a") "b" else "a") }
    }
}
