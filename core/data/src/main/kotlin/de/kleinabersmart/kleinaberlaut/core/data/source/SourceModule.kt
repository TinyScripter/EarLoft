package de.kleinabersmart.kleinaberlaut.core.data.source

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface SourceModule {
    @Binds
    fun bindMusicDataSources(registry: DataSourceRegistry): MusicDataSources
}
