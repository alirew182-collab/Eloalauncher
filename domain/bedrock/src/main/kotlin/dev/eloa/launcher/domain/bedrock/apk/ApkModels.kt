package dev.eloa.launcher.domain.bedrock.apk

object KnownPackages {
    const val MINECRAFT_BEDROCK = "com.mojang.minecraftpe"
    const val MINECRAFT_EDUCATION = "com.mojang.minecraftedu"
    val MINECRAFT = setOf(MINECRAFT_BEDROCK, MINECRAFT_EDUCATION)
}

data class SignatureInfo(
    val verified: Boolean,
    val schemes: List<String>,
    /** SHA-256 fingerprints (lower-case hex) of signer certificates. */
    val certificateSha256: List<String>,
    val certificateSubjects: List<String>,
    val errors: List<String>,
    val warnings: List<String>,
)

data class ApkInfo(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int?,
    /** Non-null for split APKs (e.g. "config.arm64_v8a"). */
    val splitName: String?,
    /** ABIs for which native libraries are present (lib/<abi>/). */
    val nativeAbis: List<String>,
    val signature: SignatureInfo,
) {
    val isMinecraft: Boolean get() = packageName in KnownPackages.MINECRAFT
    val isBase: Boolean get() = splitName == null

    /** ABI targeted by a config split (config.arm64_v8a → arm64-v8a). */
    val splitAbi: String? get() = splitName?.removePrefix("config.")?.replace('_', '-')
        ?.takeIf { it in ApkInspector.KNOWN_ABIS }
}

/** A base APK and its splits that must be installed together. */
data class ApkSet(val base: ApkInfo, val splits: List<ApkInfo>) {
    val all: List<ApkInfo> get() = listOf(base) + splits
    val packageName get() = base.packageName
    val versionCode get() = base.versionCode
    val totalSize get() = all.sumOf { it.sizeBytes }
    val abis: List<String> get() = (all.flatMap { it.nativeAbis } + splits.mapNotNull { it.splitAbi }).distinct()
}

enum class Severity { INFO, WARNING, ERROR }

data class Finding(val severity: Severity, val code: String, val message: String)

sealed interface ApkInspection {
    data class Ok(val info: ApkInfo, val findings: List<Finding>) : ApkInspection
    data class Failed(val fileName: String, val reason: String, val code: String) : ApkInspection
}
