package dev.eloa.launcher.domain.bedrock.apk

/** Device facts needed to judge APK compatibility. */
data class DeviceSpec(val sdkInt: Int, val supportedAbis: List<String>)

enum class CompatStatus { COMPATIBLE, WARNING, INCOMPATIBLE }

data class CompatibilityReport(val status: CompatStatus, val reasons: List<String>)

object CompatibilityChecker {
    fun check(set: ApkSet, device: DeviceSpec): CompatibilityReport {
        val reasons = ArrayList<String>()
        var status = CompatStatus.COMPATIBLE
        val minSdk = set.all.maxOf { it.minSdk }
        if (minSdk > device.sdkInt) {
            status = CompatStatus.INCOMPATIBLE
            reasons += "Requires Android API $minSdk, device has API ${device.sdkInt}."
        }
        val abis = set.abis
        if (abis.isNotEmpty() && abis.none { it in device.supportedAbis }) {
            status = CompatStatus.INCOMPATIBLE
            reasons += "Native code for ${abis.joinToString()} only; device supports ${device.supportedAbis.joinToString()}."
        } else if (abis.isNotEmpty() && device.supportedAbis.firstOrNull() !in abis) {
            if (status == CompatStatus.COMPATIBLE) status = CompatStatus.WARNING
            reasons += "Device primary ABI ${device.supportedAbis.firstOrNull()} not included; a slower compatible ABI will be used."
        }
        if (abis.isEmpty() && set.base.isMinecraft) {
            if (status == CompatStatus.COMPATIBLE) status = CompatStatus.WARNING
            reasons += "No native libraries found; the ABI split APK is probably missing."
        }
        if (set.all.any { !it.signature.verified }) {
            status = CompatStatus.INCOMPATIBLE
            reasons += "Invalid signature — Android Package Installer will reject it."
        }
        if (reasons.isEmpty()) reasons += "Compatible with this device."
        return CompatibilityReport(status, reasons)
    }
}

/** What is currently installed for a package (queried via PackageManager on device). */
data class InstalledPackage(val versionCode: Long, val versionName: String?, val certificateSha256: Set<String>)

sealed interface InstallDecision {
    data object FreshInstall : InstallDecision
    data object Update : InstallDecision
    data object Reinstall : InstallDecision
    /** Android forbids replacing an app signed by a different key. */
    data class SignatureConflict(val installedCerts: Set<String>, val newCerts: Set<String>) : InstallDecision
    /** Android forbids version downgrades for regular apps. */
    data class Downgrade(val installed: Long, val requested: Long) : InstallDecision
    data class Blocked(val reason: String) : InstallDecision
}

object InstallPlanner {
    fun decide(set: ApkSet, installed: InstalledPackage?, compat: CompatibilityReport): InstallDecision {
        if (compat.status == CompatStatus.INCOMPATIBLE) return InstallDecision.Blocked(compat.reasons.joinToString(" "))
        if (installed == null) return InstallDecision.FreshInstall
        val newCerts = set.base.signature.certificateSha256.toSet()
        if (installed.certificateSha256.isNotEmpty() && newCerts.isNotEmpty() && installed.certificateSha256 != newCerts) {
            return InstallDecision.SignatureConflict(installed.certificateSha256, newCerts)
        }
        return when {
            set.versionCode < installed.versionCode -> InstallDecision.Downgrade(installed.versionCode, set.versionCode)
            set.versionCode == installed.versionCode -> InstallDecision.Reinstall
            else -> InstallDecision.Update
        }
    }

    /** Human-readable explanation with the supported way forward. */
    fun explain(decision: InstallDecision): String = when (decision) {
        InstallDecision.FreshInstall -> "The package is not installed. It will be installed through the Android Package Installer."
        InstallDecision.Update -> "A newer version will replace the installed one. Game data is preserved."
        InstallDecision.Reinstall -> "The same version is already installed; it will be reinstalled."
        is InstallDecision.SignatureConflict ->
            "The installed copy is signed with a different certificate. Android does not allow replacing it. " +
                "Supported option: back up your worlds (export them from the game), uninstall the current app via system settings, then install this version."
        is InstallDecision.Downgrade ->
            "The installed version (${decision.installed}) is newer than the selected one (${decision.requested}). Android does not allow downgrades. " +
                "Supported option: back up your worlds, uninstall the current version, then install the older one. Worlds from newer versions may not open in older ones."
        is InstallDecision.Blocked -> "Installation is not possible: ${decision.reason}"
    }
}
