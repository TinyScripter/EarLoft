package de.kleinabersmart.kleinaberlaut.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.kleinaberlaut.core.download.DownloadScheduler
import de.kleinabersmart.kleinaberlaut.core.download.WorkManagerDownloadScheduler

@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadModule {
    @Binds
    fun bindScheduler(scheduler: WorkManagerDownloadScheduler): DownloadScheduler
}
