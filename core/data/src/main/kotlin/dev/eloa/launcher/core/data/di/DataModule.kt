package dev.eloa.launcher.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.LauncherDatabase
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.logging.DbLogger
import dev.eloa.launcher.core.data.security.KeystoreSecretCipher
import dev.eloa.launcher.core.data.security.SecretCipher
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): LauncherDatabase =
        Room.databaseBuilder(ctx, LauncherDatabase::class.java, LauncherDatabase.NAME).addMigrations(LauncherDatabase.MIGRATION_1_2, LauncherDatabase.MIGRATION_2_3).build()

    @Provides fun bedrockDao(db: LauncherDatabase) = db.bedrockDao()
    @Provides fun addonDao(db: LauncherDatabase) = db.addonDao()
    @Provides fun profileDao(db: LauncherDatabase) = db.profileDao()
    @Provides fun accountDao(db: LauncherDatabase) = db.accountDao()
    @Provides fun logDao(db: LauncherDatabase) = db.logDao()
    @Provides fun launchHistoryDao(db: LauncherDatabase) = db.launchHistoryDao()
    @Provides fun worldBackupDao(db: LauncherDatabase) = db.worldBackupDao()
    @Provides fun notificationDao(db: LauncherDatabase) = db.notificationDao()

    @Provides @Singleton
    fun settings(@ApplicationContext ctx: Context) = SettingsRepository(ctx.settingsStore)

    @Provides @Singleton
    fun dbLogger(db: LauncherDatabase, @AppScope scope: CoroutineScope): DbLogger = DbLogger(db.logDao(), scope)

    @Provides @Singleton
    fun logger(impl: DbLogger): AppLogger = impl

    @Provides @Singleton
    fun storage(@ApplicationContext ctx: Context) = LauncherStorage(ctx).also { it.ensureBaseDirs() }

    @Provides @Singleton
    fun deviceInfo(@ApplicationContext ctx: Context) = DeviceInfoProvider(ctx)
}

/** Separate so tests can replace the hardware-backed cipher (Robolectric has no AndroidKeyStore). */
@Module
@InstallIn(SingletonComponent::class)
object CipherModule {
    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()
}
