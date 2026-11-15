package dev.eloa.launcher.core.data.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

data class GpuInfo(val renderer: String?, val vendor: String?, val glVersion: String?)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String?,
    val socModel: String?,
    val hardware: String,
    val cpuName: String?,
    val cpuCores: Int,
    val cpuMaxFreqMhz: List<Int>,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
    val sdkInt: Int,
    val androidRelease: String,
    val glesVersion: String,
    val gpu: GpuInfo,
    val vulkanSupported: Boolean,
    val vulkanVersion: String?,
    val thermalStatus: String?,
    val thermalHeadroom: Float?,
    val batteryTempC: Float?,
) {
    val primaryAbi: String get() = supportedAbis.firstOrNull() ?: "unknown"
    val is64Bit: Boolean get() = supportedAbis.any { it.contains("64") }
    val totalRamMb: Long get() = totalRamBytes / 1_048_576
    val availRamMb: Long get() = availRamBytes / 1_048_576
}

/** Collects hardware facts using only public Android APIs and world-readable sysfs/procfs. */
class DeviceInfoProvider(private val context: Context) {
    @Volatile private var cachedGpu: GpuInfo? = null

    fun collect(includeGpu: Boolean = true): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.packageManager
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        val vkVersionFeature = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN } else null,
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } else null,
            hardware = Build.HARDWARE,
            cpuName = readCpuName(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqMhz = readCpuFreqs(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            lowMemory = mi.lowMemory,
            isLowRamDevice = am.isLowRamDevice,
            memoryClassMb = am.memoryClass,
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            glesVersion = "${gles shr 16}.${gles and 0xFFFF}",
            gpu = if (includeGpu) gpuInfo() else GpuInfo(null, null, null),
            vulkanSupported = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
            vulkanVersion = vkVersionFeature?.version?.takeIf { it != 0 }?.let { v -> "${v shr 22}.${(v shr 12) and 0x3FF}.${v and 0xFFF}" },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) thermalName(power.currentThermalStatus) else null,
            thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(10).takeIf { !it.isNaN() } else null,
            batteryTempC = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
        )
    }

    fun memorySnapshot(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem to mi.totalMem
    }

    private fun thermalName(s: Int) = when (s) {
        0 -> "None"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "Unknown"
    }

    private fun readCpuName(): String? = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }?.substringAfter(':')?.trim()
    }.getOrNull()

    private fun readCpuFreqs(): List<Int> = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 }.getOrNull()
    }

    /** Creates a 1×1 offscreen EGL context to read the GPU renderer string. Cached. */
    fun gpuInfo(): GpuInfo {
        cachedGpu?.let { return it }
        val info = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return@runCatching GpuInfo(null, null, null)
            try {
                val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) return@runCatching GpuInfo(null, null, null)
                val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                val surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                try {
                    EGL14.eglMakeCurrent(display, surf, surf, ctx)
                    GpuInfo(GLES20.glGetString(GLES20.GL_RENDERER), GLES20.glGetString(GLES20.GL_VENDOR), GLES20.glGetString(GLES20.GL_VERSION))
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surf)
                    EGL14.eglDestroyContext(display, ctx)
                }
            } finally {
                EGL14.eglTerminate(display)
            }
        }.getOrElse { GpuInfo(null, null, null) }
        cachedGpu = info
        return info
    }
}
