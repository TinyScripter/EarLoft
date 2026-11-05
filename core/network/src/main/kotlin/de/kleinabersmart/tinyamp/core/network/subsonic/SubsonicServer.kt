package de.kleinabersmart.tinyamp.core.network.subsonic

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
    /** Basis-URL für Cast-Empfänger, z. B. die Adresse im Heimnetz; `null` = [baseUrl]. */
    val castBaseUrl: HttpUrl? = null,
) {
    /** Derselbe Server, wie ihn ein Cast-Empfänger erreicht: mit [castBaseUrl], sonst unverändert. */
    fun forCast(): SubsonicServer = castBaseUrl?.let { copy(baseUrl = it, castBaseUrl = null) } ?: this

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
        fun normalizeBaseUrl(input: String): HttpUrl? = normalize(input, defaultScheme = "https")

        /**
         * Wie [normalizeBaseUrl], aber für die Cast-Adresse: Ohne Schema gilt `http://`, denn gemeint ist meist die
         * Adresse im Heimnetz (z. B. `192.168.0.10:4533`).
         */
        fun normalizeCastUrl(input: String): HttpUrl? = normalize(input, defaultScheme = "http")

        private fun normalize(input: String, defaultScheme: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "$defaultScheme://$trimmed"
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
