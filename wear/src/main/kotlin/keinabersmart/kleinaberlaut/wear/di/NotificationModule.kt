package keinabersmart.kleinaberlaut.wear.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.refresh.NewContent
import keinabersmart.kleinaberlaut.core.data.refresh.NewContentNotifier

/**
 * Die Uhr gleicht weder Podcasts noch Bücher ab (die Worker aus `core:data` laufen hier nie, Hilt verlangt aber eine
 * Bindung), also gibt es auch nichts zu melden.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NotificationModule {
    @Provides
    fun provideNewContentNotifier(): NewContentNotifier = object : NewContentNotifier {
        override fun show(content: NewContent) = Unit
    }
}
