package de.kleinabersmart.earloft.core.network.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Covers practically never change. Regardless of the server's cache headers they may therefore
 * stay in the HTTP cache for a long time – this way they also show up offline. Applies to Subsonic (`getCoverArt`) and
 * Jellyfin (`Items/<id>/Images/Primary`).
 */
class CoverArtCacheInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful || !chain.request().url.isCoverArt()) return response
        if (response.header("Content-Type")?.startsWith("image/") != true) return response
        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .build()
    }

    private fun HttpUrl.isCoverArt(): Boolean =
        encodedPath.endsWith("/rest/getCoverArt") || encodedPath.endsWith("/Images/Primary")

    private companion object {
        const val MAX_AGE_SECONDS = 60L * 60 * 24 * 90
    }
}
