package dev.eloa.launcher.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.eloa.launcher.core.common.logging.LogLevel
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.db.LauncherDatabase
import dev.eloa.launcher.core.data.db.LogEntity
import dev.eloa.launcher.core.data.logging.DbLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauncherDatabase

    private fun open() = Room.databaseBuilder(ctx, LauncherDatabase::class.java, "test.db").allowMainThreadQueries().build()

    @Before fun setUp() { ctx.deleteDatabase("test.db"); db = open() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase("test.db") }

    private fun profile(id: String, name: String = id) = JavaProfileEntity(id = id, name = name, minecraftVersion = "1.20.1", loader = "FABRIC", gameDirName = id, createdAt = System.currentTimeMillis())

    @Test fun `profiles persist across database reopen (restart recovery)`() = runBlocking {
        db.profileDao().upsert(profile("a", "Survival"))
        db.profileDao().setInstallState("a", "INSTALLED", null)
        db.close()
        db = open()
        val p = db.profileDao().byId("a")!!
        assertEquals("Survival", p.name); assertEquals("INSTALLED", p.installState)
        assertEquals(1, db.profileDao().countByDir("a"))
    }

    @Test fun `profile flow emits updates`() = runBlocking {
        db.profileDao().observeAll().test {
            assertEquals(0, awaitItem().size)
            db.profileDao().upsert(profile("x"))
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `bedrock duplicate sha rejected by unique index`() = runBlocking {
        val e = BedrockVersionEntity(displayName = "MC", packageName = "com.mojang.minecraftpe", versionName = "1.21", versionCode = 1, abis = "arm64-v8a", minSdk = 26, targetSdk = 34,
            totalSize = 10, baseSha256 = "abc", certSha256 = "c", signatureValid = true, compatStatus = "COMPATIBLE", compatReasons = "", storageDir = "/x", apkFiles = "base.apk", importedAt = 1)
        db.bedrockDao().insert(e)
        var failed = false
        try { db.bedrockDao().insert(e) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertNotNull(db.bedrockDao().bySha("abc"))
    }

    @Test fun `only one active account`() = runBlocking {
        val a = AccountEntity("1", "A", null, byteArrayOf(1), 0, true, null, 0, 0)
        db.accountDao().upsert(a); db.accountDao().upsert(a.copy(id = "2", username = "B", active = false))
        db.accountDao().setActive("2")
        assertEquals("B", db.accountDao().active()!!.username)
        assertFalse(a.toString().contains("[B"))
    }

    @Test fun `log table is trimmed to max entries and filters work`() = runBlocking {
        val items = (1..120).map { LogEntity(timestamp = it.toLong(), level = if (it % 10 == 0) "ERROR" else "INFO", component = "C", event = "event $it", errorType = null, stackTrace = null, context = null) }
        db.logDao().insertAndTrim(items, max = 100)
        assertEquals(100, db.logDao().count())
        assertEquals(21L, db.logDao().allAscending().first().timestamp)
        db.logDao().observe(listOf("ERROR"), "", 1000).test { assertEquals(10, awaitItem().size); cancelAndIgnoreRemainingEvents() }
        db.logDao().observe(listOf("INFO", "ERROR"), "event 11", 1000).test { assertEquals(listOf(110L, 119L, 118L, 117L, 116L, 115L, 114L, 113L, 112L, 111L).size + 0, awaitItem().size); cancelAndIgnoreRemainingEvents() }
    }

    @Test fun `db logger redacts secrets and respects level`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val logger = DbLogger(db.logDao(), scope, minLevel = LogLevel.INFO, maxEntries = 50, mirrorToLogcat = false)
        logger.d("T", "debug hidden")
        logger.e("Auth", "failed with access_token=SECRET123", RuntimeException("Bearer abcdef"), mapOf("refreshToken" to "RT", "file" to "x.jar"))
        val entries = withTimeout(5_000) { var l = db.logDao().allAscending(); while (l.isEmpty()) { delay(20); l = db.logDao().allAscending() }; l }
        assertEquals(1, entries.size)
        val e = entries.single()
        assertFalse(e.event.contains("SECRET123")); assertFalse(e.stackTrace!!.contains("abcdef")); assertFalse(e.context!!.contains("RT\""))
        assertTrue(e.context!!.contains("x.jar"))
        assertEquals("java.lang.RuntimeException", e.errorType)
        assertTrue(DbLogger.format(e).contains("[ERROR] Auth"))
    }
}
