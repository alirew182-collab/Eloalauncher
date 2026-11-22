package dev.eloa.launcher.core.data.performance

import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.domain.java.options.CloudMode
import dev.eloa.launcher.domain.java.options.GameOptions
import dev.eloa.launcher.domain.java.options.GraphicsQuality
import dev.eloa.launcher.domain.java.options.ParticleLevel

/**
 * Concrete parameters a mode maps to. These only bound resource usage (heap, view distance, frame
 * cap, render resolution, launcher activity). No FPS gain is promised and no system setting changes.
 */
data class PresetSpec(
    val preset: PerformancePreset,
    val javaMaxMemoryMb: Int,
    val game: GameOptions,
    /** Render resolution scale for the runtime backend (1 = native). */
    val resolutionScale: Float,
    val maxConcurrentDownloads: Int,
    val uiAnimations: Boolean,
    val blur: Boolean,
    val uiRefreshCapHz: Int,
    val cacheLimitMb: Int,
) {
    val renderDistance: Int get() = game.renderDistance
}

data class HardwareSummary(val totalRamMb: Long, val cpuCores: Int, val is64Bit: Boolean, val glesMajor: Int, val lowRamDevice: Boolean)

enum class DeviceTier { LOW, MID, HIGH }

object PerformanceAdvisor {
    fun tier(hw: HardwareSummary): DeviceTier = when {
        hw.lowRamDevice || hw.totalRamMb < 3_000 || !hw.is64Bit || hw.glesMajor < 3 -> DeviceTier.LOW
        hw.totalRamMb < 6_000 || hw.cpuCores <= 4 -> DeviceTier.MID
        else -> DeviceTier.HIGH
    }

    /** Starting mode from measurable hardware facts. */
    fun recommend(hw: HardwareSummary): PerformancePreset = when (tier(hw)) {
        DeviceTier.LOW -> PerformancePreset.EXTREME_PERFORMANCE
        DeviceTier.MID -> PerformancePreset.PERFORMANCE
        DeviceTier.HIGH -> PerformancePreset.BALANCED
    }

    /** Upper bound for the Java heap that still leaves room for Android + GPU drivers. */
    fun safeMaxMemoryMb(totalRamMb: Long): Int {
        val reserve = when {
            totalRamMb <= 3_000 -> 1_400
            totalRamMb <= 6_000 -> 2_000
            else -> 3_000
        }
        return (totalRamMb - reserve).coerceIn(512, 12_288).toInt()
    }

    /** Suggested heap: about a third of RAM, never above the safe limit. */
    fun suggestedMemoryMb(totalRamMb: Long): Int = (totalRamMb / 3).toInt().coerceIn(768, 4096).coerceAtMost(safeMaxMemoryMb(totalRamMb)).let { (it / 128) * 128 }

    fun spec(preset: PerformancePreset, hw: HardwareSummary): PresetSpec {
        val safe = safeMaxMemoryMb(hw.totalRamMb)
        val t = tier(hw)
        fun mem(fraction: Double, floor: Int, cap: Int) = ((hw.totalRamMb * fraction).toInt().coerceIn(floor, cap).coerceAtMost(safe).coerceAtLeast(512) / 128) * 128
        val rdBonus = when (t) { DeviceTier.LOW -> -2; DeviceTier.MID -> 0; DeviceTier.HIGH -> 2 }
        return when (preset) {
            PerformancePreset.BATTERY_SAVER -> PresetSpec(preset, mem(0.25, 768, 2048),
                GameOptions(renderDistance = 6 + rdBonus / 2, simulationDistance = 5, graphics = GraphicsQuality.FAST, vsync = true, maxFps = 30, mipmapLevels = 2,
                    particles = ParticleLevel.DECREASED, clouds = CloudMode.OFF, entityShadows = false, smoothLighting = true, biomeBlendRadius = 1),
                resolutionScale = 0.75f, maxConcurrentDownloads = 3, uiAnimations = false, blur = false, uiRefreshCapHz = 60, cacheLimitMb = 256)
            PerformancePreset.BALANCED -> PresetSpec(preset, mem(0.33, 1024, 3072),
                GameOptions(renderDistance = 8 + rdBonus, simulationDistance = 6, graphics = GraphicsQuality.FANCY, vsync = true, maxFps = 60, mipmapLevels = 4,
                    particles = ParticleLevel.ALL, clouds = CloudMode.FAST, entityShadows = true, smoothLighting = true, biomeBlendRadius = 2),
                resolutionScale = 1f, maxConcurrentDownloads = 6, uiAnimations = true, blur = t == DeviceTier.HIGH, uiRefreshCapHz = 0, cacheLimitMb = 512)
            PerformancePreset.PERFORMANCE -> PresetSpec(preset, mem(0.36, 1024, 4096),
                GameOptions(renderDistance = 7 + rdBonus / 2, simulationDistance = 5, graphics = GraphicsQuality.FAST, vsync = false, maxFps = 120, mipmapLevels = 2,
                    particles = ParticleLevel.DECREASED, clouds = CloudMode.OFF, entityShadows = false, smoothLighting = true, biomeBlendRadius = 1),
                resolutionScale = 0.85f, maxConcurrentDownloads = 8, uiAnimations = true, blur = false, uiRefreshCapHz = 0, cacheLimitMb = 512)
            PerformancePreset.EXTREME_PERFORMANCE -> PresetSpec(preset, mem(0.30, 768, 3072),
                GameOptions(renderDistance = 4, simulationDistance = 5, graphics = GraphicsQuality.FAST, vsync = false, maxFps = 60, mipmapLevels = 0,
                    particles = ParticleLevel.MINIMAL, clouds = CloudMode.OFF, entityShadows = false, smoothLighting = false, biomeBlendRadius = 0),
                resolutionScale = 0.6f, maxConcurrentDownloads = 4, uiAnimations = false, blur = false, uiRefreshCapHz = 0, cacheLimitMb = 256)
            PerformancePreset.CUSTOM -> PresetSpec(preset, mem(0.33, 1024, 4096), GameOptions(), 1f, 6, true, true, 0, 512)
        }
    }

    /** Warning text code when the requested allocation is risky, else null. */
    fun memoryWarning(requestedMb: Int, totalRamMb: Long): MemoryWarning? {
        val safe = safeMaxMemoryMb(totalRamMb)
        return when {
            requestedMb < 512 -> MemoryWarning.TOO_LOW
            requestedMb > safe -> MemoryWarning.ABOVE_SAFE
            else -> null
        }
    }
}

enum class MemoryWarning { TOO_LOW, ABOVE_SAFE }
