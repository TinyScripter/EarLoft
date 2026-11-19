package de.kleinabersmart.earloft.wear.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier

/**
 * The watch syncs neither podcasts nor books (the workers from `core:data` never run here, but Hilt requires a
 * binding), so there is nothing to report either.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NotificationModule {
    @Provides
    fun provideNewContentNotifier(): NewContentNotifier = object : NewContentNotifier {
        override suspend fun show(content: NewContent) = Unit
    }
}
