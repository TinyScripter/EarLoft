package de.kleinabersmart.earloft.core.playback.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.spoken.BookKeyRenamer
import de.kleinabersmart.earloft.core.playback.QueuePersistence
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.playback.SessionPlayerFactory
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PlaybackModule {

    /** LRU cache for streams (concept 7.2). There may only be one instance per folder. */
    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideStreamCache(@ApplicationContext context: Context): SimpleCache =
        SimpleCache(
            File(context.cacheDir, "streams"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )

    private const val STREAM_CACHE_BYTES = 1024L * 1024 * 1024
}

/** The phone app binds a cast-capable player, the watch doesn't. */
@Module
@InstallIn(SingletonComponent::class)
internal interface OptionalPlaybackModule {
    @BindsOptionalOf
    fun sessionPlayerFactory(): SessionPlayerFactory

    /** "Recently played" stores the episode's key; it moves along when the source changes (concept 12.10). */
    @Binds
    @IntoSet
    fun queuePersistence(persistence: QueuePersistence): BookKeyRenamer
}
