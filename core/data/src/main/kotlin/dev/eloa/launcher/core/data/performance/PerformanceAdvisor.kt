package dev.eloa.launcher.core.data.performance

import dev.eloa.launcher.core.data.settings.PerformancePreset

/** Concrete parameters a preset maps to. No FPS promises — these only bound resource usage. */
data class PresetSpec(
    val preset: PerformancePreset,
    val javaMaxMemoryMb: Int,
    val renderDistance: Int,
    val maxConcurrentDownloads: Int,
    val uiAnimations: Boolean,
    val blur: Boolean,
    val uiRefreshCapHz: Int,
    val cacheLimitMb: Int,
)

data class HardwareSummary(val totalRamMb: Long, val cpuCores: Int, val is64Bit: Boolean, val glesMajor: Int, val lowRamDevice: Boolean)

object PerformanceAdvisor {
    /** Picks a starting preset from measurable hardware facts. */
    fun recommend(hw: HardwareSummary): PerformancePreset = when {
        hw.lowRamDevice || hw.totalRamMb < 3_000 || !hw.is64Bit -> PerformancePreset.ULTRA_LOW
        hw.totalRamMb < 4_500 || hw.cpuCores <= 4 -> PerformancePreset.LOW
        hw.totalRamMb < 6_500 -> PerformancePreset.BALANCED
        hw.totalRamMb < 10_500 || hw.glesMajor < 3 -> PerformancePreset.HIGH
        else -> PerformancePreset.ULTRA
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

    fun spec(preset: PerformancePreset, hw: HardwareSummary): PresetSpec {
        val safe = safeMaxMemoryMb(hw.totalRamMb)
        fun mem(fraction: Double, floor: Int, cap: Int) = (hw.totalRamMb * fraction).toInt().coerceIn(floor, cap).coerceAtMost(safe).coerceAtLeast(512)
        return when (preset) {
            PerformancePreset.ULTRA_LOW -> PresetSpec(preset, mem(0.22, 512, 1024), 4, 2, false, false, 30, 128)
            PerformancePreset.LOW -> PresetSpec(preset, mem(0.28, 768, 1536), 6, 3, false, false, 60, 256)
            PerformancePreset.BALANCED -> PresetSpec(preset, mem(0.33, 1024, 2560), 8, 6, true, false, 0, 512)
            PerformancePreset.HIGH -> PresetSpec(preset, mem(0.38, 2048, 4096), 12, 8, true, true, 0, 1024)
            PerformancePreset.ULTRA -> PresetSpec(preset, mem(0.42, 3072, 8192), 16, 10, true, true, 0, 2048)
            PerformancePreset.CUSTOM -> PresetSpec(preset, mem(0.33, 1024, 4096), 8, 6, true, true, 0, 512)
        }
    }

    /** Warning text when the requested allocation is risky, else null. */
    fun memoryWarning(requestedMb: Int, totalRamMb: Long): String? {
        val safe = safeMaxMemoryMb(totalRamMb)
        return when {
            requestedMb < 512 -> "Less than 512 MB is not enough to start Minecraft."
            requestedMb > safe -> "$requestedMb MB exceeds the safe limit ($safe MB) for a device with $totalRamMb MB RAM; Android may terminate the game."
            else -> null
        }
    }
}
