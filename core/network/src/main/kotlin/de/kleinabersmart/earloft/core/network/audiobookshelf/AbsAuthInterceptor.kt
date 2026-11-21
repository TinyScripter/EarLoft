package de.kleinabersmart.earloft.core.network.audiobookshelf

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Sets `Authorization: Bearer <token>` for requests to known Audiobookshelf servers (concept 12.1, 12.11).
 *
 * Registered as a *network* interceptor so the header doesn't end up in cache keys; the media URLs
 * (`/api/items/<id>/file/<ino>`) therefore contain no secrets. Which token was sent goes into the request's
 * [AbsSentToken], so [AbsTokenAuthenticator] knows on 401 which one was rejected.
 */
class AbsAuthInterceptor @Inject constructor(
    private val registry: AbsServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val server = registry.forUrl(original.url) ?: return chain.proceed(original)
        if (original.header("Authorization") != null) return chain.proceed(original)
        original.tag(AbsSentToken::class.java)?.token = server.token
        return chain.proceed(original.newBuilder().header("Authorization", "Bearer ${server.token}").build())
    }
}

/**
 * Attaches an empty [AbsSentToken] to requests to known Audiobookshelf servers that don't carry their own header (streams,
 * downloads, covers). Registered as an *application* interceptor: the authenticator sees the request as it left the
 * application interceptors, with this very object; [AbsAuthInterceptor] fills it further down. Attaching the tag to the
 * response there no longer worked, OkHttp replaces the response's request on the way up (review H3).
 */
class AbsSentTokenInterceptor @Inject constructor(
    private val registry: AbsServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null || request.tag(AbsSentToken::class.java) != null) return chain.proceed(request)
        if (registry.forUrl(request.url) == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().tag(AbsSentToken::class.java, AbsSentToken()).build())
    }
}

/** The token [AbsAuthInterceptor] sent; `null` as long as it hasn't sent one. */
internal class AbsSentToken {
    @Volatile
    var token: String? = null
}
