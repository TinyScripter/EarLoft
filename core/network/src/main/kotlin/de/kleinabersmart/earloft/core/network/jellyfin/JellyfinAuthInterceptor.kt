package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import okhttp3.HttpUrl
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
 *
 * A protected feed on the host of a Jellyfin at the host root keeps its own credentials (review2 J40, G35): its fetch
 * (tag [FeedAuth]) and the files in its folder ([FeedAuthRegistry]) get no Jellyfin token, which would replace their Basic auth
 * and go to the feed's application.
 */
class JellyfinAuthInterceptor @Inject constructor(
    private val registry: JellyfinServerRegistry,
    private val clientInfo: JellyfinClientInfo,
    private val feedAuth: FeedAuthRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val tagged = original.tag(JellyfinServer::class.java)?.takeIf { original.url.acceptsCredentialsOf(it.baseUrl) }
        if (tagged == null && original.tag(FeedAuth::class.java) != null) return chain.proceed(original)
        val server = tagged
            ?: registry.forUrl(original.url)?.takeUnless { inProtectedFeedFolder(original.url, it) }
            ?: return chain.proceed(original)
        val authorized = original.newBuilder().header(AUTHORIZATION, clientInfo.authorization(server.deviceId, server.token)).build()
        return chain.proceed(authorized).newBuilder().request(original).build()
    }

    /** Whether [url] lies in the folder of a protected feed that is deeper than the base path of [server]. */
    private fun inProtectedFeedFolder(url: HttpUrl, server: JellyfinServer): Boolean {
        val feed = feedAuth.forUrl(url) ?: return false
        val folder = feed.scope.pathSegments.dropLast(1)
        val base = server.baseUrl.pathSegments.filter { it.isNotEmpty() }
        return folder.size > base.size && url.pathSegments.take(folder.size) == folder
    }

    internal companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
