package de.kleinabersmart.earloft.core.network.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsTokenAuthenticator
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinAuthInterceptor
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinClientInfo
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthInterceptor
import de.kleinabersmart.earloft.core.network.subsonic.CoverArtCacheInterceptor
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicAuthInterceptor
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
        absTokenAuthenticator: AbsTokenAuthenticator,
        jellyfinAuthInterceptor: JellyfinAuthInterceptor,
        coverArtCacheInterceptor: CoverArtCacheInterceptor,
        feedAuthInterceptor: FeedAuthInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
            .addNetworkInterceptor(authInterceptor)
            .addNetworkInterceptor(absAuthInterceptor)
            .addNetworkInterceptor(jellyfinAuthInterceptor)
            // Nach den Servern: Ihre Anmeldung geht auf demselben Host vor.
            .addNetworkInterceptor(feedAuthInterceptor)
            .addNetworkInterceptor(coverArtCacheInterceptor)
            .authenticator(absTokenAuthenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** Gerätename und App-Version, wie sie die Jellyfin-Geräteliste anzeigt. */
    @Provides
    @Singleton
    fun provideJellyfinClientInfo(@ApplicationContext context: Context): JellyfinClientInfo {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return JellyfinClientInfo(deviceName = Build.MODEL ?: "Android", version = version ?: "1")
    }

    private const val HTTP_CACHE_BYTES = 100L * 1024 * 1024
}
