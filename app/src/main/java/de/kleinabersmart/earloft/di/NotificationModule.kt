package de.kleinabersmart.earloft.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.notification.AppNewContentNotifier

@Module
@InstallIn(SingletonComponent::class)
internal interface NotificationModule {
    @Binds
    fun bindNewContentNotifier(notifier: AppNewContentNotifier): NewContentNotifier
}
