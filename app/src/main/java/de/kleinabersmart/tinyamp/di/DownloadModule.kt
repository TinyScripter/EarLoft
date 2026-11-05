package de.kleinabersmart.tinyamp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.tinyamp.core.download.DownloadScheduler
import de.kleinabersmart.tinyamp.core.download.WorkManagerDownloadScheduler

@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadModule {
    @Binds
    fun bindScheduler(scheduler: WorkManagerDownloadScheduler): DownloadScheduler
}
