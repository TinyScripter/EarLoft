package de.kleinabersmart.earloft.wear.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.wear.sync.PhoneSyncRecheck
import de.kleinabersmart.earloft.wear.sync.PhoneSyncWorker

@Module
@InstallIn(SingletonComponent::class)
internal object SyncModule {
    @Provides
    fun providePhoneSyncRecheck(@ApplicationContext context: Context): PhoneSyncRecheck =
        PhoneSyncRecheck { delayMs -> PhoneSyncWorker.enqueueIn(context, delayMs) }
}
