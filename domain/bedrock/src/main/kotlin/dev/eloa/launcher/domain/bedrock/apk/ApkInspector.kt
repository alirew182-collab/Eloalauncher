package dev.eloa.launcher.domain.bedrock.apk

import com.android.apksig.ApkVerifier
import dev.eloa.launcher.core.common.archive.ArchiveLimitException
import dev.eloa.launcher.core.common.archive.CorruptArchiveException
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.hash.Hashing.toHex
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Inspects APK files without installing them: binary manifest, ABIs, integrity (CRC of every
 * entry) and the APK signature (v1/v2/v3/v3.1/v4 via Google's apksig).
 */
class ApkInspector(private val verifyEntries: Boolean = true) {

    companion object {
        val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "riscv64")
        private const val ATTR_VERSION_CODE = 0x0101021b
        private const val ATTR_VERSION_NAME = 0x0101021c
        private const val ATTR_MIN_SDK = 0x0101020c
        private const val ATTR_TARGET_SDK = 0x01010270
        private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
        private const val MAX_MANIFEST = 8 * 1024 * 1024
    }

    fun inspect(file: File, onProgress: (Float) -> Unit = {}): ApkInspection {
        val name = file.name
        if (!file.isFile || file.length() == 0L) return ApkInspection.Failed(name, "File is empty or missing", "EMPTY")
        if (!SafeZip.isZip(file)) return ApkInspection.Failed(name, "Not an APK (ZIP signature missing)", "NOT_ZIP")
        val findings = ArrayList<Finding>()
        try {
            SafeZip.open(file).use { zip ->
                val manifestBytes = zip.readBytes("AndroidManifest.xml", MAX_MANIFEST)
                    ?: return ApkInspection.Failed(name, "AndroidManifest.xml is missing — not an Android package", "NO_MANIFEST")
                val elements = try { AxmlParser.parse(manifestBytes) } catch (e: AxmlParseException) {
                    return ApkInspection.Failed(name, "Corrupted AndroidManifest.xml: ${e.message}", "BAD_MANIFEST")
                }
                val manifest = elements.firstOrNull { it.name == "manifest" }
                    ?: return ApkInspection.Failed(name, "<manifest> element not found", "BAD_MANIFEST")
                val pkg = manifest.attr("package")?.asString()?.takeIf { it.isNotBlank() }
                    ?: return ApkInspection.Failed(name, "Package name missing in manifest", "NO_PACKAGE")
                val vcLow = manifest.attr("versionCode", ATTR_VERSION_CODE)?.asInt() ?: 0
                val vcHigh = manifest.attr("versionCodeMajor", ATTR_VERSION_CODE_MAJOR)?.asInt() ?: 0
                val versionCode = (vcHigh.toLong() shl 32) or (vcLow.toLong() and 0xFFFFFFFFL)
                val versionName = manifest.attr("versionName", ATTR_VERSION_NAME)?.asString()
                val split = manifest.attr("split")?.asString()?.takeIf { it.isNotBlank() }
                val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
                val minSdk = usesSdk?.attr("minSdkVersion", ATTR_MIN_SDK)?.asInt() ?: 1
                val targetSdk = usesSdk?.attr("targetSdkVersion", ATTR_TARGET_SDK)?.asInt()

                val abis = zip.entries.asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .mapNotNull { it.name.split('/').getOrNull(1) }
                    .filter { it in KNOWN_ABIS }.distinct().toList()
                onProgress(0.1f)
                if (verifyEntries) zip.verifyIntegrity { done, total -> onProgress(0.1f + 0.5f * done / total) }
                if (pkg in KnownPackages.MINECRAFT && split == null && abis.isEmpty()) {
                    findings += Finding(Severity.WARNING, "NO_NATIVE_LIBS",
                        "Base APK has no native libraries; ABI-specific split APKs must be imported together with it.")
                }
                onProgress(0.6f)
                val signature = verifySignature(file)
                onProgress(0.95f)
                if (!signature.verified) {
                    findings += Finding(Severity.ERROR, "SIGNATURE_INVALID",
                        "APK signature is invalid or missing: ${signature.errors.firstOrNull() ?: "unknown"}. Android will refuse to install it.")
                }
                val sha = Hashing.digest(file, HashAlgorithm.SHA256)
                onProgress(1f)
                return ApkInspection.Ok(
                    ApkInfo(name, file.length(), sha, pkg, versionCode, versionName, minSdk, targetSdk, split, abis, signature),
                    findings,
                )
            }
        } catch (e: CorruptArchiveException) {
            return ApkInspection.Failed(name, "File is damaged: ${e.message}", "CORRUPT")
        } catch (e: ArchiveLimitException) {
            return ApkInspection.Failed(name, "Archive rejected by safety limits: ${e.message}", "LIMITS")
        } catch (e: IOException) {
            return ApkInspection.Failed(name, "Cannot read file: ${e.message}", "IO")
        }
    }

    fun verifySignature(file: File): SignatureInfo = try {
        val result = ApkVerifier.Builder(file).build().verify()
        val schemes = buildList {
            if (result.isVerifiedUsingV1Scheme) add("v1")
            if (result.isVerifiedUsingV2Scheme) add("v2")
            if (result.isVerifiedUsingV3Scheme) add("v3")
            if (result.isVerifiedUsingV31Scheme) add("v3.1")
            if (result.isVerifiedUsingV4Scheme) add("v4")
        }
        val certs = result.signerCertificates.orEmpty()
        val errors = (result.errors + result.v1SchemeSigners.flatMap { it.errors } +
            result.v2SchemeSigners.flatMap { it.errors } + result.v3SchemeSigners.flatMap { it.errors })
            .map { it.toString() }.distinct()
        val warnings = (result.warnings + result.v1SchemeSigners.flatMap { it.warnings } +
            result.v2SchemeSigners.flatMap { it.warnings }).map { it.toString() }.distinct()
        SignatureInfo(
            verified = result.isVerified,
            schemes = schemes,
            certificateSha256 = certs.map { MessageDigest.getInstance("SHA-256").digest(it.encoded).toHex() },
            certificateSubjects = certs.map { it.subjectX500Principal.name },
            errors = errors,
            warnings = warnings,
        )
    } catch (e: Exception) {
        // apksig throws ApkFormatException / IOException / NoSuchAlgorithmException for broken files.
        SignatureInfo(false, emptyList(), emptyList(), emptyList(), listOf(e.message ?: e.javaClass.simpleName), emptyList())
    }
}
