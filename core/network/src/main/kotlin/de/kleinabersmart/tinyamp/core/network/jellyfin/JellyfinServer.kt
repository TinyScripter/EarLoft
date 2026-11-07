package de.kleinabersmart.tinyamp.core.network.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ein angemeldeter Jellyfin-Server (Konzept 14) mit normalisierter Basis-URL (endet immer auf `/`).
 * [token] gehört zum Gerät [deviceId]; meldet sich dasselbe Gerät neu an, verwirft der Server den alten Token.
 */
data class JellyfinServer(
    val sourceId: String,
    val baseUrl: HttpUrl,
    val userId: String,
    val token: String,
) {
    override fun toString() = "JellyfinServer($sourceId, $baseUrl)"

    val deviceId: String get() = deviceIdFor(sourceId)

    fun endpoint(path: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments(path.trimStart('/'))

    /** Jellyfin hat keinen festen API-Pfad: Alles unter der Basis-URL gehört zum Server. */
    fun owns(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme &&
            url.host == baseUrl.host &&
            url.port == baseUrl.port &&
            url.encodedPath.startsWith(baseUrl.encodedPath)

    companion object {
        /** Je eingerichtetem Server ein eigenes Gerät, damit zwei Konten auf demselben Server sich nicht abmelden. */
        fun deviceIdFor(sourceId: String) = "tinyamp-$sourceId"

        /**
         * Ergänzt `https://`, schneidet die Weboberfläche (`/web/…`, `#/home`) ab und sorgt für den
         * abschließenden `/`. `null` bei ungültiger Eingabe.
         */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() } ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }.takeWhile { it != "web" }
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

/** Wie sich die App beim Server ausweist (Kopfzeile `Authorization: MediaBrowser …`). */
class JellyfinClientInfo(
    private val deviceName: String,
    private val version: String,
) {
    /** [token] `null` nur für die Anmeldung selbst. */
    fun authorization(deviceId: String, token: String?): String = buildString {
        append("MediaBrowser Client=\"").append(CLIENT_NAME)
        append("\", Device=\"").append(encode(deviceName))
        append("\", DeviceId=\"").append(encode(deviceId))
        append("\", Version=\"").append(encode(version)).append('"')
        if (token != null) append(", Token=\"").append(encode(token)).append('"')
    }

    /** Der Server dekodiert die Werte; so bleiben Kopfzeilen ASCII (OkHttp lehnt anderes ab) und frei von `"`. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val CLIENT_NAME = "TinyAmp"
    }
}

/**
 * Bekannte Server, damit [JellyfinAuthInterceptor] auch Anfragen authentifiziert, die nicht von [JellyfinApi]
 * stammen (Media3-Streams, Downloads, Cover).
 */
@Singleton
class JellyfinServerRegistry @Inject constructor() {

    @Volatile
    private var servers: Map<String, JellyfinServer> = emptyMap()

    fun replaceAll(newServers: Collection<JellyfinServer>) {
        servers = newServers.associateBy { it.sourceId }
    }

    operator fun get(sourceId: String): JellyfinServer? = servers[sourceId]

    /** Bei verschachtelten Basis-URLs gewinnt die längste. */
    fun forUrl(url: HttpUrl): JellyfinServer? =
        servers.values.filter { it.owns(url) }.maxByOrNull { it.baseUrl.encodedPath.length }
}
