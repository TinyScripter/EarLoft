package keinabersmart.kleinaberlaut.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.refresh.NewContentNotifier
import keinabersmart.kleinaberlaut.notification.AppNewContentNotifier

@Module
@InstallIn(SingletonComponent::class)
internal interface NotificationModule {
    @Binds
    fun bindNewContentNotifier(notifier: AppNewContentNotifier): NewContentNotifier
}
