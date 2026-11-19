package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Appends the Subsonic auth parameters (concept 6.3).
 *
 * Registered as a *network* interceptor: the HTTP cache therefore only sees the URL without salt and
 * token, and the response is rewritten back to the original request, so that neither
 * cache keys nor cache files contain secrets.
 *
 * Credentials come from the request tag [SubsonicServer] (set by [SubsonicApi], after a redirect only
 * to the same host) or, for streams and covers, from the [SubsonicServerRegistry].
 */
class SubsonicAuthInterceptor @Inject constructor(
    private val registry: SubsonicServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val credentials = original.tag(SubsonicServer::class.java)?.takeIf { original.url.acceptsCredentialsOf(it.baseUrl) }?.credentials
            ?: registry.forUrl(original.url)?.credentials
            ?: return chain.proceed(original)
        if (original.url.queryParameter("u") != null || original.url.queryParameter("apiKey") != null) {
            return chain.proceed(original)
        }

        val response = chain.proceed(original.newBuilder().url(original.url.withAuth(credentials)).build())
        return response.newBuilder().request(original).build()
    }

    internal companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "EarLoft"
        private const val SALT_BYTES = 8
        private val random = SecureRandom()

        fun md5Hex(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex()

        fun HttpUrl.withAuth(credentials: SubsonicCredentials): HttpUrl = newBuilder().apply {
            when (credentials) {
                is SubsonicCredentials.ApiKey -> addQueryParameter("apiKey", credentials.key)
                is SubsonicCredentials.Password -> {
                    val salt = randomSalt()
                    addQueryParameter("u", credentials.username)
                    addQueryParameter("t", md5Hex(credentials.password + salt))
                    addQueryParameter("s", salt)
                }
            }
            if (queryParameter("v") == null) addQueryParameter("v", API_VERSION)
            if (queryParameter("c") == null) addQueryParameter("c", CLIENT_NAME)
            if (queryParameter("f") == null) addQueryParameter("f", "json")
        }.build()

        private fun randomSalt(): String = ByteArray(SALT_BYTES).also(random::nextBytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/**
 * [url] with embedded credentials – only for receivers that don't load via our OkHttpClient
 * (Chromecast). Never store or log such URLs: token and salt are valid indefinitely.
 */
fun SubsonicServer.authenticatedUrl(url: HttpUrl): HttpUrl =
    with(SubsonicAuthInterceptor) { url.withAuth(credentials) }
