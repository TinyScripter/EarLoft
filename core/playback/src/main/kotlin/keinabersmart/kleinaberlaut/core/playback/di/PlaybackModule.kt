package keinabersmart.kleinaberlaut.core.playback.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.playback.SessionPlayerFactory
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PlaybackModule {

    /** LRU-Cache für Streams (Konzept 7.2). Pro Ordner darf es nur eine Instanz geben. */
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

/** Die Handy-App bindet einen Cast-fähigen Player, die Uhr nicht. */
@Module
@InstallIn(SingletonComponent::class)
internal interface OptionalPlaybackModule {
    @BindsOptionalOf
    fun sessionPlayerFactory(): SessionPlayerFactory
}
