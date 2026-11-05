package de.kleinabersmart.kleinaberlaut.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.kleinaberlaut.core.data.refresh.NewContentNotifier
import de.kleinabersmart.kleinaberlaut.notification.AppNewContentNotifier

@Module
@InstallIn(SingletonComponent::class)
internal interface NotificationModule {
    @Binds
    fun bindNewContentNotifier(notifier: AppNewContentNotifier): NewContentNotifier
}
