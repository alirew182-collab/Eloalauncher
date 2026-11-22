package dev.eloa.launcher.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.HardwareSummary
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.security.InMemorySecretCipher
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 34])
class SettingsAndMiscTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `settings persist and are validated`() = runBlocking {
        val file = File(ctx.filesDir, "s${System.nanoTime()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertEquals(PerformancePreset.BALANCED, repo.current().performancePreset)
        repo.update { it.copy(themeMode = "DARK", maxConcurrentDownloads = 99, selectedEdition = Edition.BEDROCK, msClientId = "  abc  ", defaultMaxMemoryMb = 10) }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        val s = repo.current()
        assertEquals("DARK", s.themeMode); assertEquals(16, s.maxConcurrentDownloads); assertEquals(Edition.BEDROCK, s.selectedEdition)
        assertEquals("abc", s.msClientId); assertEquals(256, s.defaultMaxMemoryMb)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test fun `cipher round trip and tamper detection`() {
        val c = InMemorySecretCipher()
        val enc = c.encrypt("token".toByteArray())
        assertArrayEquals("token".toByteArray(), c.decrypt(enc))
        enc[enc.size - 1] = (enc.last().toInt() xor 1).toByte()
        var failed = false
        try { c.decrypt(enc) } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test fun `performance presets for different devices`() {
        val weak = HardwareSummary(2_000, 4, true, 3, true)
        val mid = HardwareSummary(6_000, 8, true, 3, false)
        val strong = HardwareSummary(16_000, 8, true, 3, false)
        assertEquals(PerformancePreset.EXTREME_PERFORMANCE, PerformanceAdvisor.recommend(weak))
        assertEquals(PerformancePreset.BALANCED, PerformanceAdvisor.recommend(mid))
        assertEquals(PerformancePreset.PERFORMANCE, PerformanceAdvisor.recommend(HardwareSummary(4_000, 8, true, 3, false)))
        assertEquals(PerformancePreset.BALANCED, PerformanceAdvisor.recommend(strong))
        assertEquals(PerformancePreset.EXTREME_PERFORMANCE, PerformanceAdvisor.recommend(HardwareSummary(8_000, 8, false, 3, false)))
        // Lighter modes never ask for more than heavier ones.
        listOf(weak, mid, strong).forEach { hw ->
            val ext = PerformanceAdvisor.spec(PerformancePreset.EXTREME_PERFORMANCE, hw)
            val bal = PerformanceAdvisor.spec(PerformancePreset.BALANCED, hw)
            val bat = PerformanceAdvisor.spec(PerformancePreset.BATTERY_SAVER, hw)
            assertTrue(ext.game.renderDistance <= bal.game.renderDistance && ext.resolutionScale < bal.resolutionScale)
            assertTrue(bat.game.maxFps <= bal.game.maxFps)
        }
        // Values stored by launcher 1.0 are migrated.
        assertEquals(PerformancePreset.EXTREME_PERFORMANCE, PerformancePreset.parse("ULTRA_LOW"))
        assertEquals(PerformancePreset.PERFORMANCE, PerformancePreset.parse("ULTRA"))
        assertNull(PerformancePreset.parse("bogus"))
        PerformancePreset.entries.forEach { p ->
            listOf(weak, mid, strong).forEach { hw ->
                val spec = PerformanceAdvisor.spec(p, hw)
                assertTrue("$p $hw", spec.javaMaxMemoryMb in 512..PerformanceAdvisor.safeMaxMemoryMb(hw.totalRamMb).coerceAtLeast(512))
            }
        }
        assertNotNull(PerformanceAdvisor.memoryWarning(8000, 4000))
        assertNull(PerformanceAdvisor.memoryWarning(1024, 4000))
        assertNotNull(PerformanceAdvisor.memoryWarning(256, 4000))
    }

    @Test fun `device info collects without crashing`() {
        val info = DeviceInfoProvider(ctx).collect(includeGpu = false)
        assertTrue(info.cpuCores > 0)
        assertTrue(info.supportedAbis.isNotEmpty())
        assertTrue(info.sdkInt >= 26)
    }

    @Test fun `storage cleanup and SAF style copy`() {
        val st = LauncherStorage(ctx)
        st.ensureBaseDirs()
        val old = File(st.importTemp, "old.tmp").apply { writeBytes(ByteArray(1000)); setLastModified(1000) }
        val part = File(st.javaRoot, "libraries/x.jar.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(500)); setLastModified(1000) }
        assertTrue(st.cleanupTemp(60_000) >= 1500)
        assertTrue(!old.exists() && !part.exists())
        val src = File(ctx.cacheDir, "pick.mcpack").apply { writeText("hello") }
        val copy = st.copyToTemp(android.net.Uri.fromFile(src), 1000)
        assertEquals("hello", copy.readText()); assertTrue(src.exists())
        var tooBig = false
        try { st.copyToTemp(android.net.Uri.fromFile(src), 2) } catch (_: Exception) { tooBig = true }
        assertTrue(tooBig)
        assertEquals("pick.mcpack", st.displayName(android.net.Uri.fromFile(src)))
    }
}
