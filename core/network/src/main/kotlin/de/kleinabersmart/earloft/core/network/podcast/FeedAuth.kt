package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Username and password of a protected feed (HTTP Basic, concept 12.10 "protected feeds"). They only apply to
 * URLs on the host of [scope] (the feed URL under which they were entered; [acceptsCredentialsOf]): if a
 * subscription moves to another host, they stay where the user sent them.
 */
data class FeedAuth(val scope: HttpUrl, val username: String, val password: String) {
    override fun toString() = "FeedAuth(${scope.host}, $username)"

    fun appliesTo(url: HttpUrl): Boolean = url.acceptsCredentialsOf(scope)

    internal val header: String get() = Credentials.basic(username, password, Charsets.UTF_8)
}

/**
 * Credentials of all subscriptions, so that [FeedAuthInterceptor] also authenticates requests that don't come from the feed fetch
 * (Media3 streams, downloads, images and chapter lists on the same host).
 */
@Singleton
class FeedAuthRegistry @Inject constructor() {

    @Volatile
    private var byFeed: Map<String, FeedAuth> = emptyMap()

    fun replaceAll(auth: Map<String, FeedAuth>) {
        byFeed = auth
    }

    /**
     * The credentials for [url]. If several feeds with different accounts are on the same host, the one whose URL
     * shares the longest path prefix with [url] matches (media and images usually sit next to the feed); otherwise the first.
     */
    fun forUrl(url: HttpUrl): FeedAuth? =
        byFeed.values.filter { it.appliesTo(url) }.maxByOrNull { sharedDirectories(it.scope, url) }

    /** Hosts of the protected feeds: what's there is not public (sharing, review D33). */
    fun hosts(): Set<String> = byFeed.values.mapTo(HashSet()) { it.scope.host }

    // Without the last segment: it's the file, not the folder.
    private fun sharedDirectories(a: HttpUrl, b: HttpUrl): Int =
        a.pathSegments.dropLast(1).zip(b.pathSegments.dropLast(1)).takeWhile { (x, y) -> x == y }.size
}

/**
 * Sets `Authorization: Basic …` for requests to the host of a protected feed. The credentials come from the
 * request tag [FeedAuth] (feed fetch; also when adding, if they aren't stored yet) or from the
 * [FeedAuthRegistry]. After a redirect to another host or from HTTPS to HTTP they are not sent along.
 * A *network* interceptor like the other auth interceptors: the header doesn't end up in cache keys, and an already
 * set authorization (Audiobookshelf, Jellyfin on the same host) stays untouched.
 */
class FeedAuthInterceptor @Inject constructor(
    private val registry: FeedAuthRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val auth = original.tag(FeedAuth::class.java)?.takeIf { it.appliesTo(original.url) }
            ?: registry.forUrl(original.url)
            ?: return chain.proceed(original)
        val authorized = original.newBuilder().header(AUTHORIZATION, auth.header).build()
        return chain.proceed(authorized).newBuilder().request(original).build()
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * If an entered feed URL carries credentials (`https://name:password@host/feed`), they are extracted: OkHttp
 * doesn't send them by itself, and only the URL without them should be stored, exported or shared.
 */
fun HttpUrl.splitCredentials(): Pair<HttpUrl, FeedAuth?> {
    if (username.isEmpty()) return this to null
    val bare = newBuilder().username("").password("").build()
    return bare to FeedAuth(bare, username, password)
}
