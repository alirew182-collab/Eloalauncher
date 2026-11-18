package dev.eloa.launcher.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.bedrock.addon.AddonInspector
import dev.eloa.launcher.domain.bedrock.apk.ApkInspector
import dev.eloa.launcher.domain.java.download.DownloadConfig
import dev.eloa.launcher.domain.java.download.DownloadManager
import dev.eloa.launcher.domain.java.net.MetaClient
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun okHttp(storage: LauncherStorage): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cache(Cache(File(storage.javaRoot, "cache/http"), 32L * 1024 * 1024))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "EloaLauncher/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .build()

    @Provides @Singleton fun metaClient(client: OkHttpClient) = MetaClient(client)
    @Provides fun apkInspector() = ApkInspector()
    @Provides fun addonInspector() = AddonInspector()
}

/** Creates download managers honouring the user's current concurrency setting. */
@Singleton
class DownloadManagerFactory @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val storage: LauncherStorage,
    private val logger: AppLogger,
) {
    suspend fun create(): DownloadManager {
        val s = settings.current()
        return DownloadManager(
            client = client,
            config = DownloadConfig(concurrency = s.maxConcurrentDownloads),
            freeSpace = { storage.freeBytes() },
            logger = logger,
        )
    }
}
