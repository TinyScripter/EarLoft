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
 * What a server reveals about itself before login (`GET /status`). The button label (`authOpenIDButtonText`)
 * is left out: it's meant for the web UI and doesn't follow the app's language.
 */
data class AbsLoginOptions(val openId: Boolean)

/** Result of a login or refresh. [refreshToken] `null`: server before 2.26, [accessToken] doesn't expire. */
data class AbsTokens(val accessToken: String, val refreshToken: String?)

/**
 * A started OpenID login (concept 12.11): the user signs in to the identity provider at [authorizationUrl],
 * which redirects back to [redirectUri]. The PKCE verifier and the session cookies are kept in memory only.
 */
class AbsOpenIdLogin internal constructor(
    val baseUrl: HttpUrl,
    val redirectUri: String,
    val authorizationUrl: HttpUrl,
    internal val state: String,
    internal val verifier: String,
    internal val cookies: CookieJar,
)

/** Audiobookshelf login via OpenID and token refresh (concept 12.11); the other calls are in [AbsApi]. */
@Singleton
class AbsAuthApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Without login; throws [AbsException] or [java.io.IOException] if no Audiobookshelf answers. */
    suspend fun loginOptions(baseUrl: HttpUrl): AbsLoginOptions = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegment("status").build()
        val status = client.newCall(Request.Builder().url(url).build()).await().use { decode<AbsStatusDto>(it.textOrThrow()) }
        if (status.app != "audiobookshelf") throw AbsException(0, "Kein Audiobookshelf-Server")
        AbsLoginOptions(openId = "openid" in status.authMethods)
    }

    /**
     * Asks the server, without a browser, for the identity provider's URL (step 2 in 12.11). The server remembers
     * [redirectUri] for the `state`; it only allows URLs from "Allowed Mobile Redirect URIs", otherwise [AbsException] with 400.
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
     * Completes the login with the identity provider's redirect (step 4). Throws [AbsException] if the
     * redirect doesn't belong to [login] or the server rejects it.
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
     * Blocks (the [AbsTokenAuthenticator] runs on an OkHttp thread, and `enqueue` could wait there for its own
     * requests to the same host). The refresh token rotates: the old one is then only valid for 10 more minutes.
     */
    fun refreshBlocking(baseUrl: HttpUrl, refreshToken: String): AbsTokens {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("auth/refresh").build())
            .header("x-refresh-token", refreshToken)
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.newCall(request).execute().use { it.tokensOrThrow() }
    }

    /** Without redirects (the `Location` is the result), without cache and with the login's cookies. */
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
        /** The redirect Audiobookshelf allows without configuration (the one of the official app). */
        const val DEFAULT_REDIRECT_URI = "audiobookshelf://oauth"

        /** Like the official app; Audiobookshelf doesn't evaluate it. */
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

        /** Expiry of an access token in seconds since 1970 (`exp` in the JWT); `null` for API keys without expiry or an unknown format. */
        fun expiresAt(token: String): Long? {
            val payload = token.split('.').getOrNull(1) ?: return null
            return try {
                val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                JSON.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** For redirects whose scheme OkHttp doesn't know (`audiobookshelf://oauth?…`): only the query matters. */
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
