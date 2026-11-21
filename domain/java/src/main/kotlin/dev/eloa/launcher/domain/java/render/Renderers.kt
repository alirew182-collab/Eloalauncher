package dev.eloa.launcher.domain.java.render

import dev.eloa.launcher.core.common.version.ComparableVersion

/**
 * OpenGL translation layers used by Android Java Edition launchers. Minecraft Java needs desktop
 * OpenGL (2.1 up to 1.16.5, 3.2 core from 1.17); Android GPUs expose OpenGL ES and/or Vulkan.
 */
enum class RendererId(val envValue: String) { GL4ES("gl4es"), MOBILEGLUES("mobileglues"), ZINK("zink"), ANGLE_GL4ES("gl4es_angle") }

enum class GpuFamily { ADRENO, MALI, POWERVR, XCLIPSE, OTHER, UNKNOWN }

data class GpuProfile(
    val rendererString: String?,
    val glesMajor: Int,
    val glesMinor: Int,
    /** "1.3.0" style, null when Vulkan is not supported. */
    val vulkanVersion: String?,
) {
    val family: GpuFamily get() = familyOf(rendererString)
    /** e.g. 650 for "Adreno (TM) 650". */
    val adrenoModel: Int? get() = if (family == GpuFamily.ADRENO) Regex("(\\d{3})").find(rendererString.orEmpty())?.value?.toInt() else null
    val gles: Int get() = glesMajor * 10 + glesMinor
    fun vulkanAtLeast(v: String) = vulkanVersion != null && ComparableVersion(vulkanVersion) >= ComparableVersion(v)

    companion object {
        fun familyOf(r: String?): GpuFamily {
            val s = r?.lowercase() ?: return GpuFamily.UNKNOWN
            return when {
                "adreno" in s -> GpuFamily.ADRENO
                "mali" in s || "immortalis" in s -> GpuFamily.MALI
                "powervr" in s || "img " in s -> GpuFamily.POWERVR
                "xclipse" in s -> GpuFamily.XCLIPSE
                s.isBlank() -> GpuFamily.UNKNOWN
                else -> GpuFamily.OTHER
            }
        }
    }
}

/** Stable codes; the UI maps them to localized text. */
enum class RendererNote {
    NEEDS_GLES2, NEEDS_GLES3, NEEDS_VULKAN_1_1, MC_TOO_NEW_FOR_GL21, LEGACY_VERSION_ONLY_TESTED_MODERN,
    MALI_VULKAN_DRIVER_ISSUES, POWERVR_LIMITED, ADRENO_TURNIP_BEST, NO_SHADERS, SODIUM_UNSUPPORTED, SLOWER_THAN_NATIVE_GLES,
    HELPS_BUGGY_GLES_DRIVERS, NOT_INSTALLED,
}

data class RendererSpec(
    val id: RendererId,
    val name: String,
    val license: String,
    val upstream: String,
    val provides: String,
    val uses: String,
    val supportedMc: String,
    val modCompat: String,
)

data class RendererVerdict(
    val spec: RendererSpec,
    val compatible: Boolean,
    val recommended: Boolean,
    val blockers: List<RendererNote>,
    val warnings: List<RendererNote>,
)

object RendererCatalog {
    val specs = listOf(
        RendererSpec(RendererId.GL4ES, "GL4ES", "MIT", "https://github.com/ptitSeb/gl4es", "OpenGL 2.1", "OpenGL ES 2.0/3.x", "≤ 1.16.5", "Most mods; no Sodium/Iris"),
        RendererSpec(RendererId.ANGLE_GL4ES, "GL4ES + ANGLE", "MIT + BSD-3-Clause", "https://chromium.googlesource.com/angle/angle", "OpenGL 2.1", "OpenGL ES on Vulkan (ANGLE)", "≤ 1.16.5", "Same as GL4ES"),
        RendererSpec(RendererId.MOBILEGLUES, "MobileGlues", "LGPL-2.1", "https://github.com/MobileGL-Dev/MobileGlues", "OpenGL 4.x (core)", "OpenGL ES 3.2 (3.0 limited)", "1.17+ (older versions partially)", "Sodium and many modern mods; shaders experimental"),
        RendererSpec(RendererId.ZINK, "Zink (Mesa)", "MIT", "https://docs.mesa3d.org/drivers/zink.html", "OpenGL 4.6", "Vulkan 1.1+", "All versions", "Broadest GL coverage incl. shaders; slower"),
    )

    private fun mcAtMost(mc: String?, limit: String): Boolean {
        val rel = mc?.let { Regex("^1\\.\\d+(\\.\\d+)?").find(it)?.value } ?: return mc != null && (mc.startsWith("a") || mc.startsWith("b") || mc.startsWith("rd-") || mc.startsWith("c0"))
        return ComparableVersion(rel) <= ComparableVersion(limit)
    }

    fun evaluate(gpu: GpuProfile, mcVersion: String?, installed: Set<RendererId>): List<RendererVerdict> {
        val legacy = mcAtMost(mcVersion, "1.16.5")
        val auto = autoSelect(gpu, mcVersion)
        return specs.map { spec ->
            val blockers = ArrayList<RendererNote>()
            val warnings = ArrayList<RendererNote>()
            when (spec.id) {
                RendererId.GL4ES -> {
                    if (gpu.gles < 20) blockers += RendererNote.NEEDS_GLES2
                    if (!legacy) blockers += RendererNote.MC_TOO_NEW_FOR_GL21
                    warnings += RendererNote.SODIUM_UNSUPPORTED
                }
                RendererId.ANGLE_GL4ES -> {
                    if (!gpu.vulkanAtLeast("1.1")) blockers += RendererNote.NEEDS_VULKAN_1_1
                    if (!legacy) blockers += RendererNote.MC_TOO_NEW_FOR_GL21
                    warnings += RendererNote.HELPS_BUGGY_GLES_DRIVERS
                    if (gpu.family == GpuFamily.MALI) warnings += RendererNote.MALI_VULKAN_DRIVER_ISSUES
                }
                RendererId.MOBILEGLUES -> {
                    if (gpu.gles < 30) blockers += RendererNote.NEEDS_GLES3
                    if (legacy) warnings += RendererNote.LEGACY_VERSION_ONLY_TESTED_MODERN
                    if (gpu.family == GpuFamily.POWERVR) warnings += RendererNote.POWERVR_LIMITED
                    warnings += RendererNote.NO_SHADERS
                }
                RendererId.ZINK -> {
                    if (!gpu.vulkanAtLeast("1.1")) blockers += RendererNote.NEEDS_VULKAN_1_1
                    when (gpu.family) {
                        GpuFamily.MALI -> warnings += RendererNote.MALI_VULKAN_DRIVER_ISSUES
                        GpuFamily.POWERVR -> warnings += RendererNote.POWERVR_LIMITED
                        GpuFamily.ADRENO -> warnings += RendererNote.ADRENO_TURNIP_BEST
                        else -> Unit
                    }
                    warnings += RendererNote.SLOWER_THAN_NATIVE_GLES
                }
            }
            if (spec.id.baseLibrary() !in installed) warnings += RendererNote.NOT_INSTALLED
            RendererVerdict(spec, blockers.isEmpty(), spec.id == auto, blockers, warnings)
        }
    }

    /** Choice for "Automatic": the lightest layer that covers the GL version this Minecraft needs on this GPU. */
    fun autoSelect(gpu: GpuProfile, mcVersion: String?): RendererId? {
        val legacy = mcAtMost(mcVersion, "1.16.5")
        return when {
            legacy && gpu.gles >= 20 -> RendererId.GL4ES
            legacy && gpu.vulkanAtLeast("1.1") -> RendererId.ANGLE_GL4ES
            !legacy && gpu.gles >= 30 && gpu.family != GpuFamily.POWERVR -> RendererId.MOBILEGLUES
            !legacy && gpu.vulkanAtLeast("1.1") && gpu.family == GpuFamily.ADRENO -> RendererId.ZINK
            !legacy && gpu.gles >= 30 -> RendererId.MOBILEGLUES
            !legacy && gpu.vulkanAtLeast("1.1") -> RendererId.ZINK
            else -> null
        }
    }

    /** Environment variables the runtime backend reads to load the chosen layer. */
    fun environment(id: RendererId, resolutionScale: Float): Map<String, String> = buildMap {
        put("ELOA_RENDERER", id.envValue)
        put("ELOA_RESOLUTION_SCALE", "%.2f".format(java.util.Locale.ROOT, resolutionScale.coerceIn(0.25f, 1f)))
        if (id == RendererId.GL4ES || id == RendererId.ANGLE_GL4ES) { put("LIBGL_ES", "2"); put("LIBGL_MIPMAP", "3"); put("LIBGL_NORMALIZE", "1") }
        if (id == RendererId.ANGLE_GL4ES) put("ELOA_GLES_DRIVER", "angle")
        if (id == RendererId.ZINK) { put("GALLIUM_DRIVER", "zink"); put("MESA_GL_VERSION_OVERRIDE", "4.6"); put("MESA_LOADER_DRIVER_OVERRIDE", "zink") }
    }
}

/** Renderer library component the layer needs (ANGLE variant reuses GL4ES plus the ANGLE library). */
fun RendererId.baseLibrary(): RendererId = if (this == RendererId.ANGLE_GL4ES) RendererId.ANGLE_GL4ES else this
