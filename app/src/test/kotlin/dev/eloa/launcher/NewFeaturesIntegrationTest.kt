package dev.eloa.launcher

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.eloa.launcher.core.data.db.AccountDao
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.security.SecretCipher
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.auth.StoredTokens
import dev.eloa.launcher.domain.java.controls.ControlPreset
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.feature.common.Strings
import dev.eloa.launcher.feature.controls.ControlsRepository
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.NoNetworkException
import dev.eloa.launcher.feature.java.NotSignedInException
import dev.eloa.launcher.feature.notifications.NoticeType
import dev.eloa.launcher.feature.notifications.NotificationCenter
import dev.eloa.launcher.feature.worlds.WorldBackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class NewFeaturesIntegrationTest : HiltTestBase() {
    @Inject lateinit var worlds: WorldBackupRepository
    @Inject lateinit var controls: ControlsRepository
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var cipher: SecretCipher
    @Inject lateinit var notifications: NotificationCenter
    @Inject lateinit var storage: LauncherStorage

    private fun levelDat(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun le(n: Int, v: Long) { val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); out.write(b, 0, n) }
        fun str(s: String) { val b = s.toByteArray(); le(2, b.size.toLong()); out.write(b) }
        out.write(10); str("")
        out.write(8); str("LevelName"); str("Island")
        out.write(3); str("GameType"); le(4, 0)
        out.write(0)
        val nbt = out.toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(nbt.size).array() + nbt
    }

    @Test fun `mcworld import, verify, damage detection and delete`() = runBlocking {
        Strings.init(context)
        val mcworld = zip(mapOf("level.dat" to levelDat(), "levelname.txt" to "Island".toByteArray(), "db/CURRENT" to ByteArray(16) { 7 }))
        val e = worlds.import(fileUri("island.mcworld", mcworld))!!
        assertEquals("Island", e.levelName)
        assertTrue(worlds.verify(e.id))
        assertNull("identical import is not duplicated", worlds.import(fileUri("again.mcworld", mcworld)))

        RandomAccessFile(File(e.storedPath), "rw").use { it.seek(it.length() / 3); it.write(byteArrayOf(9, 9, 9, 9)) }
        assertFalse(worlds.verify(e.id))
        assertTrue("damaged file creates a notification", notifications.items.first().any { it.type == NoticeType.DAMAGED_FILE.name })

        worlds.delete(e.id)
        assertTrue(worlds.backups.first().isEmpty())
        assertFalse(File(e.storedPath).exists())
    }

    @Test fun `non-world archives are rejected on import`() = runBlocking {
        val failure = runCatching { worlds.import(fileUri("pack.mcworld", zip(mapOf("manifest.json" to "{}".toByteArray())))) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(worlds.backups.first().isEmpty())
    }

    @Test fun `control layouts seed presets, save, import and publish the active one`() = runBlocking {
        val seeded = controls.load()
        assertEquals(5, seeded.size)
        val l = controls.createFromPreset(ControlPreset.PVP, "My PVP")
        controls.save(l.copy(name = "Arena", scale = 1.2f), previousName = "My PVP")
        val names = controls.load().map { it.name }
        assertTrue("Arena" in names); assertFalse("My PVP" in names)
        controls.setActive("Arena")
        assertTrue(File(storage.root, "controls/active.json").readText().contains("\"Arena\""))

        val exported = File(context.cacheDir, "arena.json")
        controls.export("Arena", android.net.Uri.fromFile(exported))
        val imported = controls.import(android.net.Uri.fromFile(exported))
        assertEquals("Arena 2", imported.name)
        assertTrue(runCatching { controls.import(fileUri("bad.json", "{\"name\":\"x\"}".toByteArray())) }.isFailure)
    }

    @Test fun `offline mode uses the stored verified identity without network`() = runBlocking {
        assertTrue(runCatching { accounts.launchIdentity(LaunchMode.OFFLINE) }.exceptionOrNull() is NotSignedInException)
        val tokens = StoredTokens("refresh", "mc-token", System.currentTimeMillis() - 1000, "2535")
        accountDao.upsert(AccountEntity("069a79f444e94726a5befca90e38aaf5", "Steve", "2535",
            cipher.encrypt(Json.encodeToString(StoredTokens.serializer(), tokens).toByteArray()), tokens.minecraftExpiresAtMs, true, null, 1, 1))
        val id = accounts.launchIdentity(LaunchMode.OFFLINE)
        assertEquals("Steve", id.playerName)
        assertEquals("mc-token", id.accessToken)
        assertFalse(id.toString().contains("mc-token"))
        // Robolectric has no validated network: Online mode must refuse instead of pretending.
        assertTrue(runCatching { accounts.launchIdentity(LaunchMode.ONLINE) }.exceptionOrNull() is NoNetworkException)
        assertNotNull(accounts.snapshot())
    }

    @Test fun `notifications are stored, throttled and cleared`() = runBlocking {
        notifications.post(NoticeType.OVERHEATING, "Hot", "first")
        notifications.post(NoticeType.OVERHEATING, "Hot", "second within throttle window")
        notifications.post(NoticeType.INFO, "Info", "x")
        assertEquals(2, notifications.items.first().size)
        assertEquals(2, notifications.unread.first())
        notifications.markAllRead()
        assertEquals(0, notifications.unread.first())
        notifications.clear()
        assertTrue(notifications.items.first().isEmpty())
    }
}

/** UI in other languages and after a configuration change. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "ru-w411dp-h891dp-xxhdpi")
class RussianUiTest : UiTestBase() {
    @Test fun `russian translation is used`() {
        compose.waitTag("home")
        waitText("Главная")
        waitText("Играть")
    }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "de-w411dp-h891dp-xxhdpi")
class GermanUiTest : UiTestBase() {
    @Test fun `german translation is used`() {
        compose.waitTag("home")
        waitText("Spielen")
    }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RotationUiTest : UiTestBase() {
    @Test fun `rotation keeps the app alive on every primary section`() {
        compose.waitTag("home")
        listOf(dev.eloa.launcher.nav.Destination.HOME, dev.eloa.launcher.nav.Destination.WORLDS, dev.eloa.launcher.nav.Destination.PERFORMANCE).forEach { d ->
            go(d)
            org.robolectric.RuntimeEnvironment.setQualifiers("+land")
            compose.activityRule.scenario.recreate()
            compose.waitTag(SCREEN_TAGS.getValue(d))
            org.robolectric.RuntimeEnvironment.setQualifiers("+port")
            compose.activityRule.scenario.recreate()
            compose.waitTag(SCREEN_TAGS.getValue(d))
        }
    }
}
