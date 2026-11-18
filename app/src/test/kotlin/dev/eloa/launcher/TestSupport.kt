package dev.eloa.launcher

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.eloa.launcher.core.data.di.CipherModule
import dev.eloa.launcher.core.data.security.InMemorySecretCipher
import dev.eloa.launcher.core.data.security.SecretCipher
import org.junit.Before
import org.junit.Rule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CipherModule::class])
object TestCipherModule {
    @Provides @Singleton fun cipher(): SecretCipher = InMemorySecretCipher()
}

/** Common Hilt + WorkManager setup for Robolectric tests. Subclasses must be @HiltAndroidTest. */
abstract class HiltTestBase {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @Inject lateinit var workerFactory: HiltWorkerFactory
    val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun baseSetUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(workerFactory).setMinimumLoggingLevel(android.util.Log.ERROR).build())
    }

    fun fileUri(name: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "picked").apply { mkdirs() }
        return Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })
    }

    fun fixtureApk(name: String): ByteArray = requireNotNull(javaClass.classLoader!!.getResource("apk/$name")).readBytes()

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    fun manifest(name: String, uuid: String, module: String, type: String = "data", deps: String = "") =
        """{"format_version":2,"header":{"name":"$name","uuid":"$uuid","version":[1,0,0],"min_engine_version":[1,20,0]},"modules":[{"type":"$type","uuid":"$module","version":[1,0,0]}],"dependencies":[$deps]}""".toByteArray()

    fun fabricMod(id: String, depends: String = "") = zip(mapOf("fabric.mod.json" to """{"schemaVersion":1,"id":"$id","version":"1.0.0","name":"${id.uppercase()}","depends":{$depends}}""".toByteArray()))
}
