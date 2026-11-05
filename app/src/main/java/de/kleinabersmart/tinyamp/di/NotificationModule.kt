package de.kleinabersmart.tinyamp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.tinyamp.core.data.refresh.NewContentNotifier
import de.kleinabersmart.tinyamp.notification.AppNewContentNotifier

@Module
@InstallIn(SingletonComponent::class)
internal interface NotificationModule {
    @Binds
    fun bindNewContentNotifier(notifier: AppNewContentNotifier): NewContentNotifier
}
