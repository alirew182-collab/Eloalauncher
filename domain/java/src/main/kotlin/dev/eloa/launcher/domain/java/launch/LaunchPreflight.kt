package dev.eloa.launcher.domain.java.launch

import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo

enum class PreflightSeverity { WARNING, BLOCKER }

data class PreflightIssue(val severity: PreflightSeverity, val code: String, val message: String, val fix: String?)

/** Checks performed right before launching; blockers prevent the launch. */
object LaunchPreflight {
    fun check(
        plan: LaunchPlan,
        runtime: JavaRuntimeInfo?,
        availableRamMb: Long,
        pendingLoaderProcessors: Int,
        backendAvailable: Boolean,
    ): List<PreflightIssue> {
        val issues = ArrayList<PreflightIssue>()
        val missing = plan.classpath.filter { !it.isFile }
        if (missing.isNotEmpty()) issues += PreflightIssue(PreflightSeverity.BLOCKER, "MISSING_FILES",
            "${missing.size} required file(s) are missing (e.g. ${missing.first().name}).", "Run “Verify & repair” for this version.")
        if (!plan.gameDir.isDirectory) issues += PreflightIssue(PreflightSeverity.BLOCKER, "NO_GAME_DIR", "Game directory does not exist.", "Repair the profile.")
        if (pendingLoaderProcessors > 0) issues += PreflightIssue(PreflightSeverity.BLOCKER, "PROCESSORS",
            "The mod loader still needs $pendingLoaderProcessors installer processor step(s) that must run on a Java runtime.", "Install a Java runtime and finish loader installation.")
        if (runtime == null) {
            issues += PreflightIssue(PreflightSeverity.BLOCKER, "NO_RUNTIME",
                "No Java ${plan.requiredJavaMajor} runtime for Android is installed.", "Import a Java runtime package in Settings → Java runtimes.")
        } else if (runtime.majorVersion < plan.requiredJavaMajor) {
            issues += PreflightIssue(PreflightSeverity.BLOCKER, "OLD_RUNTIME",
                "Version ${plan.versionId} needs Java ${plan.requiredJavaMajor}, installed runtime is Java ${runtime.majorVersion}.", "Import a newer Java runtime.")
        }
        val maxMb = plan.jvmArgs.firstOrNull { it.startsWith("-Xmx") }?.removePrefix("-Xmx")?.removeSuffix("M")?.toLongOrNull()
        if (maxMb != null && maxMb > availableRamMb) issues += PreflightIssue(PreflightSeverity.WARNING, "LOW_RAM",
            "Allocated ${maxMb} MB but only $availableRamMb MB is currently free; the system may kill the game.", "Lower the memory allocation or close other apps.")
        if (!backendAvailable) issues += PreflightIssue(PreflightSeverity.BLOCKER, "NO_BACKEND",
            "No Java Edition execution backend (JVM + LWJGL + OpenGL translation layer for Android) is installed in this build.",
            "The profile is fully prepared; launching requires a runtime backend plug-in.")
        return issues
    }
}
