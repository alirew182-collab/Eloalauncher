package dev.eloa.launcher.domain.bedrock

import dev.eloa.launcher.domain.bedrock.apk.ApkInspection
import dev.eloa.launcher.domain.bedrock.apk.ApkInspector
import dev.eloa.launcher.domain.bedrock.apk.AxmlParseException
import dev.eloa.launcher.domain.bedrock.apk.AxmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkInspectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val inspector = ApkInspector()

    private fun ok(name: String) = inspector.inspect(Fixtures.apk(name)) as ApkInspection.Ok

    @Test fun `parses base apk manifest`() {
        val r = ok("base.apk")
        with(r.info) {
            assertEquals("com.mojang.minecraftpe", packageName)
            assertEquals(1234L, versionCode)
            assertEquals("1.21.0.03", versionName)
            assertEquals(26, minSdk)
            assertEquals(34, targetSdk)
            assertNull(splitName)
            assertTrue(isMinecraft)
            assertTrue(isBase)
            assertEquals(64, sha256.length)
        }
    }

    @Test fun `verifies signature and exposes certificate`() {
        val r = ok("base.apk")
        assertTrue(r.info.signature.verified)
        assertTrue("v2" in r.info.signature.schemes)
        assertEquals(1, r.info.signature.certificateSha256.size)
        assertTrue(r.info.signature.certificateSubjects.single().contains("Fixture A"))
        assertTrue(r.findings.any { it.code == "NO_NATIVE_LIBS" })
    }

    @Test fun `split apk detects abi`() {
        val r = ok("split-arm64.apk")
        assertEquals("config.arm64_v8a", r.info.splitName)
        assertEquals(listOf("arm64-v8a"), r.info.nativeAbis)
        assertEquals("arm64-v8a", r.info.splitAbi)
    }

    @Test fun `unsigned apk is flagged`() {
        val r = ok("base-unsigned.apk")
        assertFalse(r.info.signature.verified)
        assertTrue(r.findings.any { it.code == "SIGNATURE_INVALID" })
    }

    @Test fun `different signer has different fingerprint`() {
        assertTrue(ok("split-arm64.apk").info.signature.certificateSha256 != ok("split-arm64-otherkey.apk").info.signature.certificateSha256)
    }

    @Test fun `truncated apk reported as damaged`() {
        val bytes = Fixtures.apk("split-arm64.apk").readBytes()
        val f = tmp.newFile("broken.apk"); f.writeBytes(bytes.copyOf(bytes.size - 3000))
        val r = inspector.inspect(f)
        assertTrue(r is ApkInspection.Failed)
    }

    @Test fun `tampered apk fails signature`() {
        val bytes = Fixtures.apk("split-arm64.apk").readBytes()
        // corrupt the stored (uncompressed) .so payload
        val idx = String(bytes, Charsets.ISO_8859_1).indexOf("ELFfixture")
        assertTrue(idx > 0)
        bytes[idx + 3] = 'X'.code.toByte()
        val f = tmp.newFile("tampered.apk"); f.writeBytes(bytes)
        when (val r = inspector.inspect(f)) {
            is ApkInspection.Failed -> Unit // CRC mismatch detected first
            is ApkInspection.Ok -> assertFalse(r.info.signature.verified)
        }
    }

    @Test fun `non zip and empty files`() {
        val t = tmp.newFile("x.apk"); t.writeText("hello")
        assertEquals("NOT_ZIP", (inspector.inspect(t) as ApkInspection.Failed).code)
        assertEquals("EMPTY", (inspector.inspect(tmp.newFile("e.apk")) as ApkInspection.Failed).code)
    }

    @Test fun `zip without manifest`() {
        val f = Fixtures.zip(tmp.newFile("nomf.apk"), mapOf("classes.dex" to ByteArray(4)))
        assertEquals("NO_MANIFEST", (inspector.inspect(f) as ApkInspection.Failed).code)
    }

    @Test fun `garbage manifest`() {
        val f = Fixtures.zip(tmp.newFile("bad.apk"), mapOf("AndroidManifest.xml" to ByteArray(64) { 7 }))
        assertEquals("BAD_MANIFEST", (inspector.inspect(f) as ApkInspection.Failed).code)
    }

    @Test(expected = AxmlParseException::class) fun `axml rejects truncated header`() { AxmlParser.parse(byteArrayOf(3, 0, 8, 0)) }

    @Test fun `axml fuzz never crashes unexpectedly`() {
        val good = java.util.zip.ZipFile(Fixtures.apk("base.apk")).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val rnd = java.util.Random(42)
        repeat(500) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[8 + rnd.nextInt(b.size - 8)] = rnd.nextInt(256).toByte() }
            try { AxmlParser.parse(b) } catch (_: AxmlParseException) { } catch (_: IndexOutOfBoundsException) {
                throw AssertionError("Unchecked bounds")
            }
        }
    }
}
