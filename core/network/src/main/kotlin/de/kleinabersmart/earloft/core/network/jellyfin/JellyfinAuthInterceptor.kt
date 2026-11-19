package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Sets `Authorization: MediaBrowser … Token="…"` for requests to known Jellyfin servers (concept 14).
 *
 * Registered as a *network* interceptor like [de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor]:
 * stream, download and cover URLs contain no secrets, cache keys stay stable. The server comes from the
 * request tag [JellyfinServer] (set by [JellyfinApi], after a redirect only to the same host) or from the
 * [JellyfinServerRegistry].
 */
class JellyfinAuthInterceptor @Inject constructor(
    private val registry: JellyfinServerRegistry,
    private val clientInfo: JellyfinClientInfo,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val server = original.tag(JellyfinServer::class.java)?.takeIf { original.url.acceptsCredentialsOf(it.baseUrl) }
            ?: registry.forUrl(original.url)
            ?: return chain.proceed(original)
        val authorized = original.newBuilder().header(AUTHORIZATION, clientInfo.authorization(server.deviceId, server.token)).build()
        return chain.proceed(authorized).newBuilder().request(original).build()
    }

    internal companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
