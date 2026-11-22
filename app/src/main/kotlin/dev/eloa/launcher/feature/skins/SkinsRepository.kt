package dev.eloa.launcher.feature.skins

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.apk.KnownPackages
import dev.eloa.launcher.domain.java.auth.MinecraftSkinApi
import dev.eloa.launcher.domain.java.skins.InvalidSkinException
import dev.eloa.launcher.domain.java.skins.SkinImage
import dev.eloa.launcher.domain.java.skins.SkinModel
import dev.eloa.launcher.domain.java.skins.Skins
import dev.eloa.launcher.feature.java.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SkinEntry(val id: String, val name: String, val model: SkinModel, val createdAt: Long, val source: String)

/** The picked image was readable but is not 64×64/64×32: it can still be used as a photo. */
class NotASkinException(val width: Int, val height: Int) : IOException("This image is $width×$height, not a 64×64 skin. Use “From photo” to build a skin from it.")

fun SkinImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
fun Bitmap.toSkinImage(): SkinImage = IntArray(width * height).let { px -> getPixels(px, 0, width, 0, 0, width, height); SkinImage(width, height, px) }

/**
 * Skin library: PNG files plus a small JSON index in private storage. Skins can be imported,
 * built from a photo, drawn, exported as PNG, packed for Bedrock and uploaded to the Microsoft
 * account's Java profile.
 */
@Singleton
class SkinsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: LauncherStorage,
    private val accounts: AccountRepository,
    private val http: OkHttpClient,
    private val logger: AppLogger,
) {
    private val dir get() = File(storage.root, "skins").apply { mkdirs() }
    private val indexFile get() = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _skins = MutableStateFlow<List<SkinEntry>>(emptyList())
    val skins: StateFlow<List<SkinEntry>> = _skins

    private fun file(id: String) = PathSafety.resolveInside(dir, "$id.png")

    suspend fun load(): List<SkinEntry> = withContext(Dispatchers.IO) {
        val list = runCatching { json.decodeFromString(ListSerializer(SkinEntry.serializer()), indexFile.readText()) }.getOrDefault(emptyList())
            .filter { file(it.id).isFile }.sortedByDescending { it.createdAt }
        _skins.value = list
        list
    }

    private fun writeIndex(list: List<SkinEntry>) {
        FileOps.writeAtomically(indexFile, json.encodeToString(ListSerializer(SkinEntry.serializer()), list).toByteArray())
        _skins.value = list.sortedByDescending { it.createdAt }
    }

    fun image(id: String): SkinImage {
        val bmp = BitmapFactory.decodeFile(file(id).absolutePath) ?: throw IOException("Skin file is damaged")
        return Skins.normalize(bmp.toSkinImage())
    }

    fun png(img: SkinImage): ByteArray = ByteArrayOutputStream().also { img.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Saves a new skin or overwrites [id]. */
    suspend fun save(img: SkinImage, name: String, model: SkinModel, id: String? = null, source: String = "EDITOR"): SkinEntry = withContext(Dispatchers.IO) {
        val normalized = Skins.normalize(img)
        val current = load()
        val entry = current.firstOrNull { it.id == id }?.copy(name = name.trim().take(40).ifEmpty { "Skin" }, model = model)
            ?: SkinEntry(UUID.randomUUID().toString(), name.trim().take(40).ifEmpty { "Skin" }, model, System.currentTimeMillis(), source)
        FileOps.writeAtomically(file(entry.id), png(normalized))
        writeIndex(current.filterNot { it.id == entry.id } + entry)
        entry
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val tmp = storage.copyToTemp(uri, 30L * 1024 * 1024)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0) throw IOException("Not an image the device can read")
            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
            return BitmapFactory.decodeFile(tmp.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IOException("Not an image the device can read")
        } finally { tmp.delete() }
    }

    /** Imports a skin PNG. Throws [NotASkinException] for ordinary pictures. */
    suspend fun importPng(uri: Uri): SkinEntry = withContext(Dispatchers.IO) {
        val bmp = decode(uri, 256)
        val img = bmp.toSkinImage()
        val skin = try { Skins.normalize(img) } catch (e: InvalidSkinException) { throw NotASkinException(bmp.width, bmp.height) }
        val name = storage.displayName(uri).substringBeforeLast('.').take(40)
        save(skin, name, Skins.detectModel(skin), source = "IMPORT")
    }

    /** Loads a photo scaled for preview and cropping (longest side ≤ 1024 px). */
    suspend fun loadPhoto(uri: Uri): Bitmap = withContext(Dispatchers.IO) { decode(uri, 1024) }

    fun skinFromPhoto(photo: Bitmap, model: SkinModel, cropX: Float, cropY: Float, scale: Float): SkinImage {
        val px = IntArray(photo.width * photo.height).also { photo.getPixels(it, 0, photo.width, 0, 0, photo.width, photo.height) }
        return Skins.fromPhoto(px, photo.width, photo.height, Skins.blank(model), model, cropX, cropY, scale)
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        writeIndex(load().map { if (it.id == id) it.copy(name = name.trim().take(40).ifEmpty { it.name }) else it })
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        file(id).delete()
        writeIndex(load().filterNot { it.id == id })
    }

    suspend fun exportPng(id: String, uri: Uri) = withContext(Dispatchers.IO) { storage.exportTo(file(id), uri) }

    /** Packs the skin as a Bedrock skin pack and returns the VIEW intent that hands it to Minecraft. */
    suspend fun bedrockPackIntent(id: String): Intent = withContext(Dispatchers.IO) {
        val e = load().firstOrNull { it.id == id } ?: throw IOException("Skin not found")
        val pack = File(storage.shareDir, PathSafety.sanitizeFileName(e.name, "skin") + ".mcpack").apply { parentFile?.mkdirs() }
        pack.writeBytes(Skins.bedrockSkinPack(e.name, file(id).readBytes(), e.model))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pack)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").setPackage(KnownPackages.MINECRAFT_BEDROCK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Sets the skin on the Microsoft account's Java profile (official Minecraft Services API). */
    suspend fun uploadToMicrosoft(id: String) {
        val e = load().firstOrNull { it.id == id } ?: throw IOException("Skin not found")
        val bytes = withContext(Dispatchers.IO) { file(id).readBytes() }
        MinecraftSkinApi(http).upload(accounts.microsoftToken(), bytes, e.model)
        logger.i("Skins", "Skin uploaded to Microsoft profile")
    }
}
