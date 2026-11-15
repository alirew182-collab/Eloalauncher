package dev.eloa.launcher.domain.bedrock.apk

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.PathSafety
import java.io.File

sealed interface ApkSetResult {
    data class Ok(val set: ApkSet, val findings: List<Finding>) : ApkSetResult
    data class Failed(val reason: String, val code: String) : ApkSetResult
}

/**
 * Groups inspected APKs into installable sets and unpacks split bundles (.apks / .xapk).
 */
object ApkSetAssembler {

    /** Groups APKs by package+versionCode and validates each group. */
    fun group(apks: List<ApkInfo>): List<ApkSetResult> =
        apks.groupBy { it.packageName to it.versionCode }.values.map { assemble(it) }

    fun assemble(apks: List<ApkInfo>): ApkSetResult {
        if (apks.isEmpty()) return ApkSetResult.Failed("No APK files", "EMPTY")
        val bases = apks.filter { it.isBase }
        if (bases.isEmpty()) return ApkSetResult.Failed(
            "Only split APKs (${apks.joinToString { it.splitName ?: it.fileName }}) were provided; the base APK is required.", "NO_BASE")
        if (bases.size > 1) return ApkSetResult.Failed("Several base APKs for the same version were provided.", "MULTIPLE_BASE")
        val base = bases.single()
        val splits = apks.filter { !it.isBase }
        if (apks.any { it.packageName != base.packageName || it.versionCode != base.versionCode }) {
            return ApkSetResult.Failed("Split APKs belong to a different package or version.", "MISMATCH")
        }
        val dupSplits = splits.groupBy { it.splitName }.filter { it.value.size > 1 }.keys
        if (dupSplits.isNotEmpty()) return ApkSetResult.Failed("Duplicate splits: ${dupSplits.joinToString()}", "DUP_SPLIT")
        if (splits.any { it.signature.certificateSha256.toSet() != base.signature.certificateSha256.toSet() }) {
            return ApkSetResult.Failed("Split APKs are signed with a different certificate than the base APK.", "SIGNER_MISMATCH")
        }
        val findings = ArrayList<Finding>()
        if (apks.any { !it.signature.verified }) findings += Finding(Severity.ERROR, "SIGNATURE_INVALID", "At least one APK has an invalid signature.")
        return ApkSetResult.Ok(ApkSet(base, splits.sortedBy { it.splitName }), findings)
    }

    fun isBundle(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "apks" || ext == "xapk" || ext == "apkm"
    }

    /**
     * Extracts the *.apk entries of a bundle into [workDir] (Zip Slip-safe) and returns them.
     * Nested directories are flattened; OBB files are ignored.
     */
    fun extractBundle(bundle: File, workDir: File): List<File> {
        workDir.mkdirs()
        SafeZip.open(bundle).use { zip ->
            val apkEntries = zip.entries.filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
            if (apkEntries.isEmpty()) throw java.io.IOException("Bundle contains no APK files")
            val used = HashSet<String>()
            return zip.extractTo(workDir, filter = { n -> apkEntries.any { it.name == n } }) { n ->
                var flat = PathSafety.sanitizeFileName(n.substringAfterLast('/'), "split.apk")
                while (!used.add(flat)) flat = "_$flat"
                flat
            }
        }
    }
}
