package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface SubsonicCredentials {

    /** Token authentication: `t = md5(password + salt)`. */
    class Password(val username: String, val password: String) : SubsonicCredentials {
        override fun toString() = "Password(username=$username)"
    }

    /** OpenSubsonic extension `apiKey`; replaces username and password. */
    class ApiKey(val key: String) : SubsonicCredentials {
        override fun toString() = "ApiKey(***)"
    }
}

/** A server with a normalized base URL (always ends with `/`). */
data class SubsonicServer(
    val sourceId: String,
    val baseUrl: HttpUrl,
    val credentials: SubsonicCredentials,
    /** OpenSubsonic extension `formPost`: parameters may go in the POST body (long song lists). */
    val supportsFormPost: Boolean = false,
) {
    fun endpoint(name: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments("rest/$name")

    /**
     * Does [url] belong to this server's REST API? Like Jellyfin (review G33) also after a proxy redirected http to https on the
     * same host ([acceptsCredentialsOf]); otherwise the credentials were missing there (review2 J39).
     */
    fun owns(url: HttpUrl): Boolean =
        url.acceptsCredentialsOf(baseUrl) && url.encodedPath.startsWith(baseUrl.encodedPath + "rest/")

    companion object {
        /**
         * Turns user input into a base URL: adds `https://`, strips a trailing
         * `/rest` or `/app` and ensures a closing `/`. `null` for invalid input.
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
