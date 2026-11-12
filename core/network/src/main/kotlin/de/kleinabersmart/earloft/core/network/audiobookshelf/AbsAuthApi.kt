package de.kleinabersmart.earloft.core.network.audiobookshelf

import de.kleinabersmart.earloft.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was ein Server vor der Anmeldung über sich verrät (`GET /status`). Die Beschriftung des Knopfs (`authOpenIDButtonText`)
 * bleibt außen vor: Sie ist für die Weboberfläche gedacht und folgt nicht der Sprache der App.
 */
data class AbsLoginOptions(val openId: Boolean)

/** Ergebnis einer Anmeldung oder Erneuerung. [refreshToken] `null`: Server vor 2.26, [accessToken] läuft nicht ab. */
data class AbsTokens(val accessToken: String, val refreshToken: String?)

/**
 * Eine begonnene Anmeldung per OpenID (Konzept 12.11): Der Nutzer meldet sich unter [authorizationUrl] beim Identity-Provider
 * an, der leitet auf [redirectUri] zurück. PKCE-Verifier und Cookies der Sitzung bleiben nur im Speicher.
 */
class AbsOpenIdLogin internal constructor(
    val baseUrl: HttpUrl,
    val redirectUri: String,
    val authorizationUrl: HttpUrl,
    internal val state: String,
    internal val verifier: String,
    internal val cookies: CookieJar,
)

/** Anmeldung bei Audiobookshelf per OpenID und Erneuerung der Tokens (Konzept 12.11); die übrigen Aufrufe in [AbsApi]. */
@Singleton
class AbsAuthApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Ohne Anmeldung; wirft [AbsException] bzw. [java.io.IOException], wenn kein Audiobookshelf antwortet. */
    suspend fun loginOptions(baseUrl: HttpUrl): AbsLoginOptions = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegment("status").build()
        val status = client.newCall(Request.Builder().url(url).build()).await().use { decode<AbsStatusDto>(it.textOrThrow()) }
        if (status.app != "audiobookshelf") throw AbsException(0, "Kein Audiobookshelf-Server")
        AbsLoginOptions(openId = "openid" in status.authMethods)
    }

    /**
     * Fragt den Server ohne Browser nach der Adresse des Identity-Providers (Schritt 2 in 12.11). Der Server merkt sich
     * [redirectUri] zum `state`; er lässt nur Adressen aus „Allowed Mobile Redirect URIs“ zu, sonst [AbsException] mit 400.
     */
    suspend fun beginOpenId(baseUrl: HttpUrl, redirectUri: String): AbsOpenIdLogin = withContext(Dispatchers.IO) {
        val verifier = randomToken(64)
        val state = randomToken(32)
        val cookies = MemoryCookieJar()
        val url = baseUrl.newBuilder().addPathSegments("auth/openid")
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("state", state)
            .build()
        val location = loginClient(cookies).newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isRedirect) throw AbsException(response.code, "HTTP ${response.code}: ${response.body.string().take(200)}")
            response.header("Location")?.let { response.request.url.resolve(it) }
        } ?: throw AbsException(0, "Weiterleitung ohne Ziel")
        AbsOpenIdLogin(baseUrl, redirectUri, location, state, verifier, cookies)
    }

    /**
     * Schließt die Anmeldung mit der Rückleitung des Identity-Providers ab (Schritt 4). Wirft [AbsException], wenn die
     * Rückleitung nicht zu [login] gehört oder der Server ablehnt.
     */
    suspend fun finishOpenId(login: AbsOpenIdLogin, redirect: HttpUrl): AbsTokens = withContext(Dispatchers.IO) {
        if (redirect.queryParameter("state") != login.state) throw AbsException(0, "Rückleitung gehört nicht zu dieser Anmeldung")
        val code = redirect.queryParameter("code")
            ?: throw AbsException(401, "Anmeldung abgelehnt: ${redirect.queryParameter("error")}")
        val url = login.baseUrl.newBuilder().addPathSegments("auth/openid/callback")
            .addQueryParameter("state", login.state)
            .addQueryParameter("code", code)
            .addQueryParameter("code_verifier", login.verifier)
            .build()
        loginClient(login.cookies).newCall(Request.Builder().url(url).build()).await().use { it.tokensOrThrow() }
    }

    /**
     * Blockiert (der [AbsTokenAuthenticator] läuft auf einem Thread von OkHttp, und `enqueue` könnte dort auf die eigenen
     * Anfragen an denselben Host warten). Der Refresh-Token rotiert: Der alte gilt danach nur noch 10 Minuten.
     */
    fun refreshBlocking(baseUrl: HttpUrl, refreshToken: String): AbsTokens {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("auth/refresh").build())
            .header("x-refresh-token", refreshToken)
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.newCall(request).execute().use { it.tokensOrThrow() }
    }

    /** Ohne Weiterleitungen (die `Location` ist das Ergebnis), ohne Cache und mit den Cookies der Anmeldung. */
    private fun loginClient(cookies: CookieJar): OkHttpClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).cache(null).cookieJar(cookies).build()

    private fun Response.tokensOrThrow(): AbsTokens {
        val user = decode<AbsLoginResponse>(textOrThrow()).user
        val access = user.accessToken ?: user.token ?: throw AbsException(0, "Antwort ohne Token")
        return AbsTokens(access, user.refreshToken?.takeIf { user.accessToken != null })
    }

    private fun Response.textOrThrow(): String {
        val text = body.string()
        if (!isSuccessful) throw AbsException(code, "HTTP $code: ${text.take(200)}")
        return text
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            JSON.decodeFromString(text)
        } catch (e: SerializationException) {
            throw AbsException(0, "Unerwartete Antwort: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw AbsException(0, "Unerwartete Antwort: ${e.message}")
        }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name } }
            this.cookies += cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        /** Die Rückleitung, die Audiobookshelf ohne Einstellung zulässt (die der offiziellen App). */
        const val DEFAULT_REDIRECT_URI = "audiobookshelf://oauth"

        /** Wie die offizielle App; Audiobookshelf wertet ihn nicht aus. */
        private const val CLIENT_ID = "Audiobookshelf-App"

        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
        private val random = SecureRandom()

        private val base64Url = Base64.getUrlEncoder().withoutPadding()

        private fun randomToken(bytes: Int): String = base64Url.encodeToString(ByteArray(bytes).also(random::nextBytes))

        private fun challenge(verifier: String): String =
            base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** Ablauf eines Access-Tokens in Sekunden seit 1970 (`exp` im JWT); `null` bei API-Keys ohne Ablauf oder fremdem Format. */
        fun expiresAt(token: String): Long? {
            val payload = token.split('.').getOrNull(1) ?: return null
            return try {
                val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                JSON.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Für Rückleitungen, deren Schema OkHttp nicht kennt (`audiobookshelf://oauth?…`): nur die Query zählt. */
        fun redirectUrl(uri: String): HttpUrl? = "https://redirect/?${uri.substringAfter('?', "")}".toHttpUrlOrNull()
    }
}

@Serializable
internal data class AbsStatusDto(
    val app: String? = null,
    val authMethods: List<String> = emptyList(),
)

@Serializable
internal data class AbsLoginResponse(val user: AbsLoginUser)

@Serializable
internal data class AbsLoginUser(val accessToken: String? = null, val refreshToken: String? = null, val token: String? = null)
