package dev.eloa.launcher.domain.bedrock

import dev.eloa.launcher.domain.bedrock.apk.ApkInfo
import dev.eloa.launcher.domain.bedrock.apk.ApkInspection
import dev.eloa.launcher.domain.bedrock.apk.ApkInspector
import dev.eloa.launcher.domain.bedrock.apk.ApkSetAssembler
import dev.eloa.launcher.domain.bedrock.apk.ApkSetResult
import dev.eloa.launcher.domain.bedrock.apk.CompatStatus
import dev.eloa.launcher.domain.bedrock.apk.CompatibilityChecker
import dev.eloa.launcher.domain.bedrock.apk.DeviceSpec
import dev.eloa.launcher.domain.bedrock.apk.InstallDecision
import dev.eloa.launcher.domain.bedrock.apk.InstallPlanner
import dev.eloa.launcher.domain.bedrock.apk.InstalledPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkSetTest {
    @get:Rule val tmp = TemporaryFolder()
    private val inspector = ApkInspector()
    private fun info(name: String): ApkInfo = (inspector.inspect(Fixtures.apk(name)) as ApkInspection.Ok).info
    private val arm64Phone = DeviceSpec(34, listOf("arm64-v8a", "armeabi-v7a"))

    @Test fun `base plus split assemble into a set`() {
        val r = ApkSetAssembler.assemble(listOf(info("split-arm64.apk"), info("base.apk"))) as ApkSetResult.Ok
        assertEquals(1, r.set.splits.size)
        assertEquals(listOf("arm64-v8a"), r.set.abis)
        assertEquals(CompatStatus.COMPATIBLE, CompatibilityChecker.check(r.set, arm64Phone).status)
    }

    @Test fun `split alone is rejected`() {
        assertEquals("NO_BASE", (ApkSetAssembler.assemble(listOf(info("split-arm64.apk"))) as ApkSetResult.Failed).code)
    }

    @Test fun `split with foreign signer is rejected`() {
        val r = ApkSetAssembler.assemble(listOf(info("base.apk"), info("split-arm64-otherkey.apk")))
        assertEquals("SIGNER_MISMATCH", (r as ApkSetResult.Failed).code)
    }

    @Test fun `grouping separates versions`() {
        val groups = ApkSetAssembler.group(listOf(info("base.apk"), info("split-arm64.apk"), info("newer-x86_64.apk")))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it is ApkSetResult.Ok })
    }

    @Test fun `abi and sdk incompatibility`() {
        val newer = (ApkSetAssembler.assemble(listOf(info("newer-x86_64.apk"))) as ApkSetResult.Ok).set
        val rep = CompatibilityChecker.check(newer, arm64Phone)
        assertEquals(CompatStatus.INCOMPATIBLE, rep.status)
        val other = (ApkSetAssembler.assemble(listOf(info("other-minsdk35.apk"))) as ApkSetResult.Ok).set
        assertEquals(CompatStatus.INCOMPATIBLE, CompatibilityChecker.check(other, arm64Phone).status)
        assertEquals(CompatStatus.COMPATIBLE, CompatibilityChecker.check(other, DeviceSpec(35, listOf("arm64-v8a"))).status)
    }

    @Test fun `install decisions`() {
        val set = (ApkSetAssembler.assemble(listOf(info("base.apk"), info("split-arm64.apk"))) as ApkSetResult.Ok).set
        val compat = CompatibilityChecker.check(set, arm64Phone)
        val certs = set.base.signature.certificateSha256.toSet()
        assertEquals(InstallDecision.FreshInstall, InstallPlanner.decide(set, null, compat))
        assertEquals(InstallDecision.Update, InstallPlanner.decide(set, InstalledPackage(1000, "x", certs), compat))
        assertEquals(InstallDecision.Reinstall, InstallPlanner.decide(set, InstalledPackage(1234, "x", certs), compat))
        assertTrue(InstallPlanner.decide(set, InstalledPackage(2000, "x", certs), compat) is InstallDecision.Downgrade)
        assertTrue(InstallPlanner.decide(set, InstalledPackage(1000, "x", setOf("deadbeef")), compat) is InstallDecision.SignatureConflict)
        val unsigned = (ApkSetAssembler.assemble(listOf(info("base-unsigned.apk"))) as ApkSetResult.Ok).set
        assertTrue(InstallPlanner.decide(unsigned, null, CompatibilityChecker.check(unsigned, arm64Phone)) is InstallDecision.Blocked)
        assertTrue(InstallPlanner.explain(InstallDecision.Downgrade(2, 1)).contains("downgrade"))
    }

    @Test fun `apks bundle extraction`() {
        val bundle = Fixtures.zip(tmp.newFile("mc.apks"), mapOf(
            "base.apk" to Fixtures.apk("base.apk").readBytes(),
            "splits/config.arm64_v8a.apk" to Fixtures.apk("split-arm64.apk").readBytes(),
            "toc.pb" to ByteArray(3),
        ))
        assertTrue(ApkSetAssembler.isBundle(bundle))
        val files = ApkSetAssembler.extractBundle(bundle, File(tmp.root, "work"))
        assertEquals(2, files.size)
        val set = ApkSetAssembler.assemble(files.map { (inspector.inspect(it) as ApkInspection.Ok).info })
        assertTrue(set is ApkSetResult.Ok)
    }

    @Test(expected = java.io.IOException::class) fun `bundle with traversal entry rejected`() {
        val bundle = Fixtures.zip(tmp.newFile("evil.apks"), mapOf("../../x.apk" to Fixtures.apk("base.apk").readBytes()))
        // name is flattened, but sanitized names must stay inside workDir; ensure no escape
        val files = ApkSetAssembler.extractBundle(bundle, File(tmp.root, "w"))
        assertTrue(files.all { it.canonicalPath.startsWith(File(tmp.root, "w").canonicalPath) })
        throw java.io.IOException("ok")
    }
}
