package de.kleinabersmart.earloft.core.network.subsonic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface SubsonicCredentials {

    /** Token-Authentifizierung: `t = md5(passwort + salt)`. */
    class Password(val username: String, val password: String) : SubsonicCredentials {
        override fun toString() = "Password(username=$username)"
    }

    /** OpenSubsonic-Extension `apiKey`; ersetzt Benutzername und Passwort. */
    class ApiKey(val key: String) : SubsonicCredentials {
        override fun toString() = "ApiKey(***)"
    }
}

/** Ein Server mit normalisierter Basis-URL (endet immer auf `/`). */
data class SubsonicServer(
    val sourceId: String,
    val baseUrl: HttpUrl,
    val credentials: SubsonicCredentials,
    /** OpenSubsonic-Extension `formPost`: Parameter dürfen im POST-Body stehen (lange Song-Listen). */
    val supportsFormPost: Boolean = false,
) {
    fun endpoint(name: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments("rest/$name")

    /** Gehört [url] zur REST-API dieses Servers? */
    fun owns(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme &&
            url.host == baseUrl.host &&
            url.port == baseUrl.port &&
            url.encodedPath.startsWith(baseUrl.encodedPath + "rest/")

    companion object {
        /**
         * Macht aus einer Nutzereingabe eine Basis-URL: ergänzt `https://`, entfernt angehängtes
         * `/rest` oder `/app` und sorgt für einen abschließenden `/`. `null` bei ungültiger Eingabe.
         */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() } ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
                .let { if (it.lastOrNull() == "rest" || it.lastOrNull() == "app") it.dropLast(1) else it }
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
