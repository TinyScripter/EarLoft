package keinabersmart.kleinaberlaut.core.network.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Hängt die Subsonic-Auth-Parameter an (Konzept 6.3).
 *
 * Als *Network*-Interceptor registriert: Der HTTP-Cache sieht dadurch nur die URL ohne Salt und
 * Token, und die Antwort wird wieder auf die ursprüngliche Anfrage umgeschrieben, damit weder
 * Cache-Schlüssel noch Cache-Dateien Secrets enthalten.
 *
 * Zugangsdaten kommen aus dem Request-Tag [SubsonicCredentials] (gesetzt von [SubsonicApi]) oder,
 * für Streams und Cover, aus der [SubsonicServerRegistry].
 */
class SubsonicAuthInterceptor @Inject constructor(
    private val registry: SubsonicServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val credentials = original.tag(SubsonicCredentials::class.java)
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
        const val CLIENT_NAME = "kleinaberlaut"
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
 * [url] mit eingebetteten Zugangsdaten – nur für Empfänger, die nicht über unseren OkHttpClient
 * laden (Chromecast). Solche URLs nie speichern oder loggen: Token und Salt gelten unbegrenzt.
 */
fun SubsonicServer.authenticatedUrl(url: HttpUrl): HttpUrl =
    with(SubsonicAuthInterceptor) { url.withAuth(credentials) }
