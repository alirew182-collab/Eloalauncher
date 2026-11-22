package dev.eloa.launcher.domain.java.skins

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class SkinModel(val apiVariant: String) { CLASSIC("classic"), SLIM("slim") }

class InvalidSkinException(message: String) : IllegalArgumentException(message)

/** A Minecraft skin texture as ARGB pixels. Always 64×64 after [Skins.normalize]. */
class SkinImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) { "pixel count mismatch" } }
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }
    fun copy() = SkinImage(width, height, pixels.copyOf())
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
}

/** A rectangle of the texture. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    operator fun contains(p: Pair<Int, Int>) = p.first in x until x + w && p.second in y until y + h
}

enum class Face { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

/** One cuboid of the player model and where its six faces live on the texture. */
data class BodyPart(val key: String, val u: Int, val v: Int, val w: Int, val h: Int, val d: Int, val overlay: Boolean) {
    fun face(f: Face): Rect = when (f) {
        Face.TOP -> Rect(u + d, v, w, d)
        Face.BOTTOM -> Rect(u + d + w, v, w, d)
        Face.RIGHT -> Rect(u, v + d, d, h)
        Face.FRONT -> Rect(u + d, v + d, w, h)
        Face.LEFT -> Rect(u + d + w, v + d, d, h)
        Face.BACK -> Rect(u + d + w + d, v + d, w, h)
    }
    val faces: List<Rect> get() = Face.entries.map(::face)
}

/**
 * Skin texture layout (the box-UV mapping Minecraft uses since 1.8) and operations on it:
 * validation, legacy 64×32 conversion, slim detection, previews, building a skin from a photo
 * and a Bedrock skin pack.
 */
object Skins {
    const val SIZE = 64

    fun parts(model: SkinModel): List<BodyPart> {
        val arm = if (model == SkinModel.SLIM) 3 else 4
        return listOf(
            BodyPart("head", 0, 0, 8, 8, 8, false), BodyPart("hat", 32, 0, 8, 8, 8, true),
            BodyPart("body", 16, 16, 8, 12, 4, false), BodyPart("jacket", 16, 32, 8, 12, 4, true),
            BodyPart("right_arm", 40, 16, arm, 12, 4, false), BodyPart("right_sleeve", 40, 32, arm, 12, 4, true),
            BodyPart("left_arm", 32, 48, arm, 12, 4, false), BodyPart("left_sleeve", 48, 48, arm, 12, 4, true),
            BodyPart("right_leg", 0, 16, 4, 12, 4, false), BodyPart("right_pants", 0, 32, 4, 12, 4, true),
            BodyPart("left_leg", 16, 48, 4, 12, 4, false), BodyPart("left_pants", 0, 48, 4, 12, 4, true),
        )
    }

    /** Part whose face contains the texel, for the editor's hover label. */
    fun partAt(model: SkinModel, x: Int, y: Int): BodyPart? = parts(model).firstOrNull { p -> p.faces.any { (x to y) in it } }

    fun alpha(argb: Int) = argb ushr 24

    /** Accepts 64×64 and legacy 64×32 (converted). Everything else is rejected with the reason. */
    fun normalize(img: SkinImage): SkinImage {
        if (img.width != SIZE || (img.height != SIZE && img.height != 32)) {
            throw InvalidSkinException("A skin must be 64×64 or 64×32 pixels, this image is ${img.width}×${img.height}.")
        }
        return if (img.height == 32) convertLegacy(img) else img
    }

    /**
     * Legacy skins have only right limbs; Minecraft mirrors them to the left side. Same here:
     * every face is flipped horizontally and the side faces swap.
     */
    fun convertLegacy(old: SkinImage): SkinImage {
        val out = SkinImage(SIZE, SIZE, IntArray(SIZE * SIZE))
        for (y in 0 until 32) for (x in 0 until SIZE) out[x, y] = old[x, y]
        fun mirror(src: BodyPart, dst: BodyPart) {
            val map = mapOf(Face.TOP to Face.TOP, Face.BOTTOM to Face.BOTTOM, Face.FRONT to Face.FRONT, Face.BACK to Face.BACK, Face.RIGHT to Face.LEFT, Face.LEFT to Face.RIGHT)
            map.forEach { (sf, df) ->
                val s = src.face(sf); val d = dst.face(df)
                for (yy in 0 until s.h) for (xx in 0 until s.w) out[d.x + (s.w - 1 - xx), d.y + yy] = old[s.x + xx, s.y + yy]
            }
        }
        val p = parts(SkinModel.CLASSIC).associateBy { it.key }
        mirror(p.getValue("right_leg"), p.getValue("left_leg"))
        mirror(p.getValue("right_arm"), p.getValue("left_arm"))
        // The legacy hat layer often has an opaque background; Minecraft treats a fully opaque hat as absent.
        return out
    }

    /** Slim (3 px arms) skins leave the 4th arm column transparent. */
    fun detectModel(img: SkinImage): SkinModel {
        val s = normalize(img)
        val transparent = (20 until 32).all { y -> alpha(s[54, y]) == 0 && alpha(s[55, y]) == 0 }
        return if (transparent) SkinModel.SLIM else SkinModel.CLASSIC
    }

    /** Front (or back) view of the player as a 16×32 image: base layer plus overlay. */
    fun preview(img: SkinImage, model: SkinModel, back: Boolean = false): SkinImage {
        val s = normalize(img)
        val out = SkinImage(16, 32, IntArray(16 * 32))
        val p = parts(model).associateBy { it.key }
        val arm = if (model == SkinModel.SLIM) 3 else 4
        val face = if (back) Face.BACK else Face.FRONT
        // Viewer's left is the player's right when seen from the front; swapped from the back.
        val leftOnScreen = if (back) "left" else "right"
        val rightOnScreen = if (back) "right" else "left"
        fun blit(part: String, dx: Int, dy: Int) {
            val r = p.getValue(part).face(face)
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val c = s[r.x + x, r.y + y]
                if (alpha(c) > 0) out[dx + x, dy + y] = c
            }
        }
        blit("head", 4, 0); blit("hat", 4, 0)
        blit("body", 4, 8); blit("jacket", 4, 8)
        blit("${leftOnScreen}_arm", 4 - arm, 8); blit(if (leftOnScreen == "right") "right_sleeve" else "left_sleeve", 4 - arm, 8)
        blit("${rightOnScreen}_arm", 12, 8); blit(if (rightOnScreen == "right") "right_sleeve" else "left_sleeve", 12, 8)
        blit("${leftOnScreen}_leg", 4, 20); blit(if (leftOnScreen == "right") "right_pants" else "left_pants", 4, 20)
        blit("${rightOnScreen}_leg", 8, 20); blit(if (rightOnScreen == "right") "right_pants" else "left_pants", 8, 20)
        return out
    }

    /** A plain original default skin (not Mojang's Steve or Alex) to start drawing from. */
    fun blank(model: SkinModel = SkinModel.CLASSIC): SkinImage {
        val img = SkinImage(SIZE, SIZE, IntArray(SIZE * SIZE))
        val skin = 0xFFC69680.toInt(); val hair = 0xFF4A3426.toInt(); val shirt = 0xFF2F7D6D.toInt(); val pants = 0xFF34466B.toInt(); val shoe = 0xFF3A3A3A.toInt()
        fun fill(part: BodyPart, color: Int) = part.faces.forEach { r -> for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) img[x, y] = color }
        val p = parts(model).associateBy { it.key }
        fill(p.getValue("head"), skin)
        listOf("body", "right_arm", "left_arm").forEach { fill(p.getValue(it), shirt) }
        listOf("right_leg", "left_leg").forEach { k ->
            fill(p.getValue(k), pants)
            p.getValue(k).faces.forEach { r -> for (y in maxOf(r.y, r.y + r.h - 2) until r.y + r.h) for (x in r.x until r.x + r.w) img[x, y] = shoe }
        }
        // hands
        listOf("right_arm", "left_arm").forEach { k -> p.getValue(k).faces.forEach { r -> for (y in maxOf(r.y, r.y + r.h - 3) until r.y + r.h) for (x in r.x until r.x + r.w) img[x, y] = skin } }
        // hair on top/back/sides, simple face
        val h = p.getValue("head")
        listOf(Face.TOP, Face.BACK).forEach { f -> val r = h.face(f); for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) img[x, y] = hair }
        listOf(Face.LEFT, Face.RIGHT, Face.FRONT).forEach { f -> val r = h.face(f); for (y in r.y until r.y + 2) for (x in r.x until r.x + r.w) img[x, y] = hair }
        val front = h.face(Face.FRONT)
        img[front.x + 2, front.y + 4] = 0xFFFFFFFF.toInt(); img[front.x + 3, front.y + 4] = 0xFF2B4C7E.toInt()
        img[front.x + 5, front.y + 4] = 0xFFFFFFFF.toInt(); img[front.x + 4, front.y + 4] = skin; img[front.x + 6, front.y + 4] = 0xFF2B4C7E.toInt()
        for (x in front.x + 3..front.x + 4) img[x, front.y + 6] = 0xFF8A5A48.toInt()
        return img
    }

    /**
     * Builds a skin from a photo: the centre square of the photo becomes the 8×8 face, the photo's
     * top and edge colours become hair on the other head faces, the rest comes from [base].
     * [photo] is ARGB with dimensions [w]×[h].
     */
    fun fromPhoto(photo: IntArray, w: Int, h: Int, base: SkinImage, model: SkinModel, cropX: Float = 0.5f, cropY: Float = 0.4f, cropScale: Float = 1f): SkinImage {
        require(w > 0 && h > 0 && photo.size == w * h) { "invalid photo" }
        val out = normalize(base).copy()
        val side = (minOf(w, h) * cropScale.coerceIn(0.1f, 1f)).toInt().coerceAtLeast(1)
        val left = ((w * cropX) - side / 2f).toInt().coerceIn(0, w - side)
        val top = ((h * cropY) - side / 2f).toInt().coerceIn(0, h - side)
        val head = parts(model).first { it.key == "head" }
        val face = head.face(Face.FRONT)
        fun avg(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = photo[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            if (n == 0L) return 0xFF000000.toInt()
            return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        val cell = side / 8f
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val x0 = left + (cx * cell).toInt(); val y0 = top + (cy * cell).toInt()
            out[face.x + cx, face.y + cy] = avg(x0, y0, maxOf(x0 + 1, left + ((cx + 1) * cell).toInt()), maxOf(y0 + 1, top + ((cy + 1) * cell).toInt()))
        }
        val hairColor = avg(left, top, left + side, top + maxOf(1, (cell * 1.5f).toInt()))
        val sideColor = avg(left, top, left + maxOf(1, cell.toInt()), top + side)
        listOf(Face.TOP, Face.BACK).forEach { f -> val r = head.face(f); for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) out[x, y] = hairColor }
        listOf(Face.LEFT, Face.RIGHT).forEach { f -> val r = head.face(f); for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) out[x, y] = if (y < r.y + 3) hairColor else sideColor }
        // Clear the hat layer over the face so the photo is visible.
        parts(model).first { it.key == "hat" }.faces.forEach { r -> for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) out[x, y] = 0 }
        return out
    }

    /** Flood fill limited to the face rectangle the start pixel belongs to, as in skin editors. */
    fun fill(img: SkinImage, model: SkinModel, x: Int, y: Int, argb: Int): Boolean {
        val bounds = parts(model).flatMap { it.faces }.firstOrNull { (x to y) in it } ?: return false
        val target = img[x, y]
        if (target == argb) return false
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(x to y) }
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            if ((cx to cy) !in bounds || img[cx, cy] != target) continue
            img[cx, cy] = argb
            stack.add(cx + 1 to cy); stack.add(cx - 1 to cy); stack.add(cx to cy + 1); stack.add(cx to cy - 1)
        }
        return true
    }

    /** True when [x],[y] belongs to any face of the model (other texels are unused by the game). */
    fun isUsed(model: SkinModel, x: Int, y: Int) = parts(model).any { p -> p.faces.any { (x to y) in it } }

    /**
     * A Bedrock skin pack (`.mcpack`) with one skin. Minecraft Bedrock imports it when the file is
     * opened with the game; the skin then appears under its name in the dressing room.
     */
    fun bedrockSkinPack(name: String, png: ByteArray, model: SkinModel, packUuid: UUID = UUID.randomUUID(), moduleUuid: UUID = UUID.randomUUID()): ByteArray {
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Skin" }.take(32)
        val serial = "Eloa" + safe.replace(" ", "")
        val manifest = """{"format_version":1,"header":{"name":"$safe","uuid":"$packUuid","version":[1,0,0]},"modules":[{"type":"skin_pack","uuid":"$moduleUuid","version":[1,0,0]}]}"""
        val geometry = if (model == SkinModel.SLIM) "geometry.humanoid.customSlim" else "geometry.humanoid.custom"
        val skins = """{"skins":[{"localization_name":"skin0","geometry":"$geometry","texture":"skin0.png","type":"free"}],"serialize_name":"$serial","localization_name":"$serial"}"""
        val lang = "skinpack.$serial=$safe\nskin.$serial.skin0=$safe\n"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("manifest.json", manifest.toByteArray()); put("skins.json", skins.toByteArray()); put("skin0.png", png)
            put("texts/en_US.lang", lang.toByteArray()); put("texts/languages.json", "[\"en_US\"]".toByteArray())
        }
        return bos.toByteArray()
    }
}
