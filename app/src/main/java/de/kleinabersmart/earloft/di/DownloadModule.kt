package de.kleinabersmart.earloft.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import de.kleinabersmart.earloft.core.download.WorkManagerDownloadScheduler

@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadModule {
    @Binds
    fun bindScheduler(scheduler: WorkManagerDownloadScheduler): DownloadScheduler
}
