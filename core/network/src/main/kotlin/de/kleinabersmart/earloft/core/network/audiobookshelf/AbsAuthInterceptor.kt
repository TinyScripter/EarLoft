package de.kleinabersmart.earloft.core.network.audiobookshelf

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Sets `Authorization: Bearer <token>` for requests to known Audiobookshelf servers (concept 12.1, 12.11).
 *
 * Registered as a *network* interceptor so the header doesn't end up in cache keys; the media URLs
 * (`/api/items/<id>/file/<ino>`) therefore contain no secrets. Which token was sent is attached as [AbsSentToken] to the
 * response's request, so [AbsTokenAuthenticator] knows on 401 which one was rejected.
 */
class AbsAuthInterceptor @Inject constructor(
    private val registry: AbsServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val server = registry.forUrl(original.url) ?: return chain.proceed(original)
        if (original.header("Authorization") != null) return chain.proceed(original)
        val response = chain.proceed(original.newBuilder().header("Authorization", "Bearer ${server.token}").build())
        return response.newBuilder().request(original.newBuilder().tag(AbsSentToken::class.java, AbsSentToken(server.token)).build()).build()
    }
}

/** The token [AbsAuthInterceptor] set. */
internal class AbsSentToken(val token: String)
