package de.kleinabersmart.kleinaberlaut.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.CoverArtCacheInterceptor
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.SubsonicAuthInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    /** Der eine Client für API, Cover und Streams (Konzept 3): gemeinsame Auth, Pools und Cache. */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        authInterceptor: SubsonicAuthInterceptor,
        absAuthInterceptor: AbsAuthInterceptor,
        coverArtCacheInterceptor: CoverArtCacheInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
            .addNetworkInterceptor(authInterceptor)
            .addNetworkInterceptor(absAuthInterceptor)
            .addNetworkInterceptor(coverArtCacheInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private const val HTTP_CACHE_BYTES = 100L * 1024 * 1024
}
