package dev.eloa.launcher.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.eloa.launcher.feature.launch.GameRuntimeBackend
import dev.eloa.launcher.feature.launch.UnavailableBackend

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {
    @Binds abstract fun backend(impl: UnavailableBackend): GameRuntimeBackend
}
