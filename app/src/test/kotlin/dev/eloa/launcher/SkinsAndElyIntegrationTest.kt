package dev.eloa.launcher

import android.graphics.Bitmap
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.eloa.launcher.core.data.db.AccountDao
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.security.SecretCipher
import dev.eloa.launcher.domain.java.auth.ElyByAuth
import dev.eloa.launcher.domain.java.auth.ElyTokens
import dev.eloa.launcher.domain.java.auth.StoredTokens
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.skins.Face
import dev.eloa.launcher.domain.java.skins.SkinImage
import dev.eloa.launcher.domain.java.skins.SkinModel
import dev.eloa.launcher.domain.java.skins.Skins
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.ELY_ID_PREFIX
import dev.eloa.launcher.feature.java.ElyRequiresOwnershipException
import dev.eloa.launcher.feature.java.TYPE_ELYBY
import dev.eloa.launcher.feature.skins.NotASkinException
import dev.eloa.launcher.feature.skins.SkinsRepository
import dev.eloa.launcher.feature.skins.toBitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [34])
class SkinsAndElyIntegrationTest : HiltTestBase() {
    @Inject lateinit var skins: SkinsRepository
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var cipher: SecretCipher

    private fun pngOf(img: SkinImage) = ByteArrayOutputStream().also { img.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test fun `skin png import, legacy conversion, rename, export, bedrock pack and delete`() = runBlocking {
        val legacy = SkinImage(64, 32, IntArray(64 * 32))
        val head = Skins.parts(SkinModel.CLASSIC).first { it.key == "head" }.face(Face.FRONT)
        for (x in 0 until 64) for (y in 0 until 32) legacy[x, y] = 0xFF336699.toInt()
        legacy[head.x, head.y] = 0xFFFF0000.toInt()
        val e = skins.importPng(fileUri("steve_old.png", pngOf(legacy)))
        assertEquals("steve_old", e.name)
        val stored = skins.image(e.id)
        assertEquals(64, stored.height)
        assertEquals(0xFFFF0000.toInt(), stored[head.x, head.y])

        skins.rename(e.id, "  Night  ")
        assertEquals("Night", skins.load().single().name)

        val out = File(context.cacheDir, "export.png")
        skins.exportPng(e.id, android.net.Uri.fromFile(out))
        assertTrue(out.length() > 0)

        val intent = skins.bedrockPackIntent(e.id)
        assertEquals("com.mojang.minecraftpe", intent.`package`)
        assertTrue(intent.data.toString().endsWith("Night.mcpack"))

        skins.delete(e.id)
        assertTrue(skins.load().isEmpty())
    }

    @Test fun `ordinary pictures are not imported as skins but can become one`() = runBlocking {
        val photo = SkinImage(120, 90, IntArray(120 * 90) { 0xFF8D5524.toInt() })
        val uri = fileUri("me.png", pngOf(photo))
        val error = runCatching { skins.importPng(uri) }.exceptionOrNull()
        assertTrue(error is NotASkinException)
        assertTrue(skins.load().isEmpty())

        val bmp = skins.loadPhoto(uri)
        val img = skins.skinFromPhoto(bmp, SkinModel.SLIM, 0.5f, 0.5f, 1f)
        val face = Skins.parts(SkinModel.SLIM).first { it.key == "head" }.face(Face.FRONT)
        assertEquals(0xFF8D5524.toInt(), img[face.x + 3, face.y + 3])
        val saved = skins.save(img, "Photo", SkinModel.SLIM, source = "PHOTO")
        assertEquals(SkinModel.SLIM, Skins.detectModel(skins.image(saved.id)))
    }

    @Test fun `ely by needs a microsoft owner and never overwrites it`() = runBlocking {
        assertTrue(runCatching { accounts.addElyBy("user", "pass", null) }.exceptionOrNull() is ElyRequiresOwnershipException)

        val uuid = "069a79f444e94726a5befca90e38aaf5"
        val ely = ElyTokens("ely-token", "client")
        accountDao.upsert(AccountEntity(ELY_ID_PREFIX + uuid, "Steve", null,
            cipher.encrypt(Json.encodeToString(ElyTokens.serializer(), ely).toByteArray()), Long.MAX_VALUE, true, null, 1, 1, TYPE_ELYBY))
        assertTrue("Ely.by alone does not unlock play", runCatching { accounts.launchIdentity(LaunchMode.OFFLINE) }.exceptionOrNull() is ElyRequiresOwnershipException)

        val ms = StoredTokens("refresh", "mc-token", System.currentTimeMillis() + 3_600_000, "2535")
        accountDao.upsert(AccountEntity(uuid, "Steve", "2535", cipher.encrypt(Json.encodeToString(StoredTokens.serializer(), ms).toByteArray()), ms.minecraftExpiresAtMs, false, null, 1, 1))
        assertEquals("same UUID, two separate accounts", 2, accountDao.observeAll().first().size)
        val id = accounts.launchIdentity(LaunchMode.OFFLINE)
        assertEquals(ElyByAuth.AUTHLIB_API, id.authServer)
        assertEquals("ely-token", id.accessToken)
        assertFalse(id.uuid.startsWith(ELY_ID_PREFIX))
    }
}
