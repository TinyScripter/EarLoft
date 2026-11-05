package de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Audiobookshelf-Server mit normalisierter Basis-URL (endet immer auf `/`) und API-Key. */
data class AbsServer(
    val serverId: String,
    val baseUrl: HttpUrl,
    val apiKey: String,
) {
    override fun toString() = "AbsServer($serverId, $baseUrl)"

    fun endpoint(path: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments(path.trimStart('/'))

    /** Gehört [url] zu diesem Server (API, Dateien, Cover)? */
    fun owns(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme &&
            url.host == baseUrl.host &&
            url.port == baseUrl.port &&
            url.encodedPath.startsWith(baseUrl.encodedPath + "api/")

    companion object {
        /** Ergänzt `https://`, entfernt Pfad-Reste hinter der Basis und sorgt für den abschließenden `/`. */
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
 * Bekannte Server, damit [AbsAuthInterceptor] auch Anfragen authentifiziert, die nicht von [AbsApi] stammen
 * (Media3-Streams, Cover).
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
