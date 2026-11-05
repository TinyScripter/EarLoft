package de.kleinabersmart.kleinaberlaut.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.kleinaberlaut.core.data.repository.PlayReporter
import de.kleinabersmart.kleinaberlaut.core.data.repository.SyncQueuePlayReporter

@Module
@InstallIn(SingletonComponent::class)
internal interface PlayReporterModule {
    @Binds
    fun bindReporter(reporter: SyncQueuePlayReporter): PlayReporter
}
