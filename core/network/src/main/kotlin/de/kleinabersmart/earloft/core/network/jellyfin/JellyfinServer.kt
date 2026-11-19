package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.model.SourceType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin emerged from Emby; both speak the same REST API with some differing paths (concept 14).
 * The differences are in [JellyfinApi].
 */
enum class JellyfinFlavor(val sourceType: SourceType, val productName: String) {
    JELLYFIN(SourceType.JELLYFIN, "Jellyfin"),
    EMBY(SourceType.EMBY, "Emby"),
    ;

    companion object {
        fun of(type: SourceType): JellyfinFlavor? = entries.firstOrNull { it.sourceType == type }
    }
}

/**
 * A signed-in Jellyfin or Emby server (concept 14) with a normalized base URL (always ends with `/`).
 * [token] belongs to the device [deviceId]; if the same device signs in again, Jellyfin discards the old token (Emby doesn't).
 */
data class JellyfinServer(
    val sourceId: String,
    val baseUrl: HttpUrl,
    val userId: String,
    val token: String,
    val flavor: JellyfinFlavor = JellyfinFlavor.JELLYFIN,
    val deviceId: String = legacyDeviceId(sourceId),
) {
    override fun toString() = "JellyfinServer($sourceId, $baseUrl)"

    fun endpoint(path: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments(path.trimStart('/'))

    /** Jellyfin and Emby have no fixed API path: everything under the base URL belongs to the server. */
    fun owns(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme &&
            url.host == baseUrl.host &&
            url.port == baseUrl.port &&
            url.encodedPath.startsWith(baseUrl.encodedPath)

    companion object {
        /**
         * Device for a new login: a separate one per server and installation, so that two accounts on the same server
         * and two devices with the same backup (same `sourceId`) don't sign each other out.
         */
        fun newDeviceId(): String = "earloft-${UUID.randomUUID()}"

        /** Device of the logins from before [newDeviceId]; their token stays valid. */
        fun legacyDeviceId(sourceId: String) = "earloft-$sourceId"

        /**
         * Adds `https://`, cuts off the web UI (`/web/…`, `#/home`) and ensures the
         * trailing `/`. `null` for invalid input.
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

/** How the app identifies itself to the server (header `Authorization: MediaBrowser …`). */
class JellyfinClientInfo(
    private val deviceName: String,
    private val version: String,
) {
    /** [token] `null` only for the login itself. */
    fun authorization(deviceId: String, token: String?): String = buildString {
        append("MediaBrowser Client=\"").append(CLIENT_NAME)
        append("\", Device=\"").append(encode(deviceName))
        append("\", DeviceId=\"").append(encode(deviceId))
        append("\", Version=\"").append(encode(version)).append('"')
        if (token != null) append(", Token=\"").append(encode(token)).append('"')
    }

    /** The server decodes the values; this keeps headers ASCII (OkHttp rejects anything else) and free of `"`. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val CLIENT_NAME = "EarLoft"
    }
}

/**
 * Known servers, so that [JellyfinAuthInterceptor] also authenticates requests that don't come from [JellyfinApi]
 * (Media3 streams, downloads, covers).
 */
@Singleton
class JellyfinServerRegistry @Inject constructor() {

    @Volatile
    private var servers: Map<String, JellyfinServer> = emptyMap()

    fun replaceAll(newServers: Collection<JellyfinServer>) {
        servers = newServers.associateBy { it.sourceId }
    }

    operator fun get(sourceId: String): JellyfinServer? = servers[sourceId]

    /** With nested base URLs the longest one wins. */
    fun forUrl(url: HttpUrl): JellyfinServer? =
        servers.values.filter { it.owns(url) }.maxByOrNull { it.baseUrl.encodedPath.length }
}
