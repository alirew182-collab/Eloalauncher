package dev.eloa.launcher.feature.bedrock

import dev.eloa.launcher.R
import dev.eloa.launcher.feature.common.str
import androidx.core.net.toUri
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.domain.bedrock.apk.InstalledPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class InstallEvent(val sessionId: Int, val success: Boolean, val message: String, val packageName: String?)

/**
 * Thin wrapper over the official Android PackageInstaller session API. Every installation
 * is confirmed by the user in the system UI; signatures and downgrades are enforced by Android.
 */
@Singleton
class PackageInstallerGateway @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun canRequestInstalls(): Boolean = pm.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun installedPackage(packageName: String): InstalledPackage? = try {
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> emptyArray()
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory?.takeLast(1)?.toTypedArray() ?: emptyArray()
            }
        } else info.signatures ?: emptyArray()
        val vc = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        InstalledPackage(vc, info.versionName, sigs.map { Hashing.digest(it.toByteArray(), HashAlgorithm.SHA256) }.toSet())
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Streams all APKs of the set into one session and commits it. Returns the session id. */
    suspend fun install(files: List<File>, packageName: String, onProgress: (Float) -> Unit): Int = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(files.sumOf { it.length() })
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val total = files.sumOf { it.length() }.coerceAtLeast(1)
            var written = 0L
            files.forEachIndexed { i, f ->
                f.inputStream().use { input ->
                    session.openWrite("apk_$i.apk", 0, f.length()).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); written += n
                            session.setStagingProgress(written.toFloat() / total)
                            onProgress(written.toFloat() / total)
                        }
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            id
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    companion object {
        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val events: SharedFlow<InstallEvent> = _events
        internal fun emit(e: InstallEvent) { _events.tryEmit(e) }
    }
}

/** Receives PackageInstaller results; asks the user for confirmation when required. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> str(R.string.inst_success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> str(R.string.inst_aborted)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> str(R.string.inst_blocked)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> str(R.string.inst_conflict)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> str(R.string.inst_incompatible)
            PackageInstaller.STATUS_FAILURE_INVALID -> str(R.string.inst_invalid)
            PackageInstaller.STATUS_FAILURE_STORAGE -> str(R.string.inst_storage)
            else -> str(R.string.inst_failed_1_s, msg ?: "-")
        }
        PackageInstallerGateway.emit(InstallEvent(sessionId, status == PackageInstaller.STATUS_SUCCESS, text, pkg))
    }
}
