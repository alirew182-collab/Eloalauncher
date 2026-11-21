package dev.eloa.launcher.domain.java.crash

/**
 * Recognises well-known failure signatures in Minecraft / JVM / launcher logs and crash reports.
 * Each finding carries a stable [code] so the UI can show a localized explanation and fix.
 */
enum class CrashCause {
    OUT_OF_MEMORY,
    NATIVE_LIBRARY_MISSING,
    WRONG_JAVA_VERSION,
    OPENGL_UNSUPPORTED,
    RENDERER_CRASH,
    MOD_MISSING_DEPENDENCY,
    MOD_INCOMPATIBLE_MINECRAFT,
    MOD_DUPLICATE,
    MIXIN_FAILURE,
    CORRUPTED_FILE,
    DISK_FULL,
    NETWORK,
    INVALID_SESSION,
    SIGNAL_CRASH,
    UNKNOWN,
}

data class CrashFinding(
    val cause: CrashCause,
    /** The log line that matched (trimmed, secrets are expected to be redacted by the caller). */
    val evidence: String,
    /** Extra detail extracted from the log (mod id, class name, required Java version…). */
    val detail: String? = null,
)

data class CrashAnalysis(val findings: List<CrashFinding>, val exceptionHeadline: String?) {
    val primary: CrashCause get() = findings.firstOrNull()?.cause ?: CrashCause.UNKNOWN
}

object CrashAnalyzer {
    private data class Rule(val cause: CrashCause, val regex: Regex, val detail: (MatchResult) -> String? = { null })

    private val CLASS_VERSION_TO_JAVA = mapOf(52 to 8, 55 to 11, 60 to 16, 61 to 17, 65 to 21, 66 to 22, 67 to 23, 68 to 24, 69 to 25)

    private val rules = listOf(
        Rule(CrashCause.OUT_OF_MEMORY, Regex("java\\.lang\\.OutOfMemoryError(: .*)?")) { it.groupValues[1].removePrefix(": ").ifBlank { null } },
        Rule(CrashCause.WRONG_JAVA_VERSION, Regex("class file version (\\d+)\\.\\d+.*?recognizes class file versions up to (\\d+)")) { m ->
            CLASS_VERSION_TO_JAVA[m.groupValues[1].toInt()]?.let { "Java $it" }
        },
        Rule(CrashCause.WRONG_JAVA_VERSION, Regex("UnsupportedClassVersionError")),
        Rule(CrashCause.NATIVE_LIBRARY_MISSING, Regex("(UnsatisfiedLinkError|dlopen failed)[: ]+(.*)")) { it.groupValues[2].take(160) },
        Rule(CrashCause.OPENGL_UNSUPPORTED, Regex("(GLFW error 65543|OpenGL [0-9.]+ (is )?(not supported|required)|Unsupported OpenGL|No OpenGL context found|GL_VERSION is null)", RegexOption.IGNORE_CASE)),
        Rule(CrashCause.RENDERER_CRASH, Regex("(libgl4es|libMobileGlues|libOSMesa|libvulkan|libEGL|libGLES|zink).*(SIGSEGV|SIGABRT|crash|fault)", RegexOption.IGNORE_CASE)),
        Rule(CrashCause.MOD_MISSING_DEPENDENCY, Regex("(?:requires|depends on) (?:any version of |version [^ ]+ of )?'?([a-z0-9_\\-]+)'?.*(?:which is missing|but it (?:is|was) not installed)", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(CrashCause.MOD_MISSING_DEPENDENCY, Regex("Missing or unsupported mandatory dependencies:?(.*)")) { it.groupValues[1].trim().ifBlank { null } },
        Rule(CrashCause.MOD_INCOMPATIBLE_MINECRAFT, Regex("[Mm]od '?([^' ]+)'? .*requires.*minecraft", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(CrashCause.MOD_DUPLICATE, Regex("(Duplicate mods? found|DuplicateModsFoundException|Found duplicate mods?)[: ]*(.*)")) { it.groupValues[2].ifBlank { null } },
        Rule(CrashCause.MIXIN_FAILURE, Regex("(MixinApplyError|InvalidMixinException|Mixin apply failed|mixin\\.transformer\\.throwables)[: ]*(.*)")) { it.groupValues[2].take(160).ifBlank { null } },
        Rule(CrashCause.CORRUPTED_FILE, Regex("(ZipException|invalid LOC header|zip END header not found|java\\.util\\.zip\\.ZipError|Corrupted|checksum mismatch)", RegexOption.IGNORE_CASE)),
        Rule(CrashCause.DISK_FULL, Regex("(No space left on device|ENOSPC)")),
        Rule(CrashCause.NETWORK, Regex("(UnknownHostException|ConnectException|SocketTimeoutException|Network is unreachable)")),
        Rule(CrashCause.INVALID_SESSION, Regex("(Invalid session|Failed to login: Invalid session|InvalidCredentialsException|401 Unauthorized)", RegexOption.IGNORE_CASE)),
        Rule(CrashCause.SIGNAL_CRASH, Regex("(Fatal signal \\d+ \\((SIG[A-Z]+)\\)|A fatal error has been detected by the Java Runtime Environment)")) { it.groupValues.getOrNull(2)?.ifBlank { null } },
    )

    private val EXCEPTION_LINE = Regex("^(?:Caused by: |Exception in thread \"[^\"]*\" )?([a-zA-Z_$][\\w$.]*(?:Exception|Error|Throwable))(: .*)?$")

    fun analyze(log: String, maxLines: Int = 20_000): CrashAnalysis {
        val lines = log.lineSequence().take(maxLines).toList()
        val findings = LinkedHashMap<CrashCause, CrashFinding>()
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            for (r in rules) {
                if (r.cause in findings) continue
                val m = r.regex.find(t) ?: continue
                findings[r.cause] = CrashFinding(r.cause, t.take(300), r.detail(m))
            }
        }
        // The deepest "Caused by" is usually the real reason.
        val headline = lines.map { it.trim() }.lastOrNull { EXCEPTION_LINE.matches(it) && it.startsWith("Caused by") }
            ?: lines.map { it.trim() }.firstOrNull { EXCEPTION_LINE.matches(it) }
        val ordered = findings.values.sortedBy { it.cause.ordinal }
        return CrashAnalysis(ordered, headline?.take(300))
    }
}
