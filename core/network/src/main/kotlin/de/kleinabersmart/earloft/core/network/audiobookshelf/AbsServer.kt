package de.kleinabersmart.earloft.core.network.audiobookshelf

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An Audiobookshelf server with a normalized base URL (always ends with `/`). [token] is the API key or, when signed in
 * via OpenID, the access token (concept 12.11), which changes hourly: the one in the [AbsServerRegistry] is authoritative.
 */
data class AbsServer(
    val serverId: String,
    val baseUrl: HttpUrl,
    val token: String,
) {
    override fun toString() = "AbsServer($serverId, $baseUrl)"

    fun endpoint(path: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments(path.trimStart('/'))

    /**
     * Does [url] belong to this server (API, files, covers)? Also after a proxy redirected http to https on the same host
     * ([acceptsCredentialsOf], review2 J39).
     */
    fun owns(url: HttpUrl): Boolean =
        url.acceptsCredentialsOf(baseUrl) && url.encodedPath.startsWith(baseUrl.encodedPath + "api/")

    companion object {
        /** Adds `https://`, removes path remnants after the base and ensures the trailing `/`. */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() } ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
                .let { if (it.lastOrNull() == "audiobookshelf") it else it.takeWhile { s -> s != "api" && s != "login" } }
            return url.newBuilder()
                .encodedPath("/")
                .apply { segments.forEach { addPathSegment(it) } }
                .addPathSegment("")
                .query(null)
                .fragment(null)
                .build()
        }
    }
}

/**
 * Known servers, so that [AbsAuthInterceptor] also authenticates requests that don't come from [AbsApi]
 * (Media3 streams, covers).
 */
@Singleton
class AbsServerRegistry @Inject constructor() {

    @Volatile
    private var servers: Map<String, AbsServer> = emptyMap()

    fun replaceAll(newServers: Collection<AbsServer>) {
        servers = newServers.associateBy { it.serverId }
    }

    fun register(server: AbsServer) {
        servers = servers + (server.serverId to server)
    }

    fun unregister(serverId: String) {
        servers = servers - serverId
    }

    operator fun get(serverId: String): AbsServer? = servers[serverId]

    fun forUrl(url: HttpUrl): AbsServer? = servers.values.firstOrNull { it.owns(url) }
}
