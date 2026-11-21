package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsSentTokenInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsTokenAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** OpenID sign-in and token renewal (concept 12.11) against a mocked Audiobookshelf. */
@RunWith(RobolectricTestRunner::class)
class AbsOpenIdTest {

    private val server = MockWebServer()
    private lateinit var db: EarLoftDatabase
    private lateinit var servers: AudiobookshelfServers
    private lateinit var renewal: AbsTokenRenewal
    private lateinit var api: AbsApi
    private lateinit var client: OkHttpClient
    private val registry = AbsServerRegistry()
    private val secrets = MemorySecrets()

    // State of the mocked server.
    private val validAccess = ConcurrentHashMap.newKeySet<String>()
    private val refreshCalls = AtomicInteger()

    @Volatile
    private var currentRefresh = "refresh-1"

    @Volatile
    private var challenge: String? = null

    /** Renewal arrived or may respond; `null` = immediately. */
    @Volatile
    private var refreshGate: Pair<CountDownLatch, CountDownLatch>? = null

    @Before
    fun setUp() {
        server.dispatcher = FakeAudiobookshelf()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        client = OkHttpClient.Builder()
            .addInterceptor(AbsSentTokenInterceptor(registry))
            .addNetworkInterceptor(AbsAuthInterceptor(registry))
            .authenticator(AbsTokenAuthenticator(registry) { renewal })
            .build()
        api = AbsApi(client)
        val authApi = AbsAuthApi(client)
        renewal = AbsTokenRenewal(secrets, registry, authApi)
        servers = AudiobookshelfServers(db.bookDao(), secrets, api, authApi, renewal, registry, CredentialsStamps(secrets))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private val baseUrl get() = server.url("/").toString()

    /** Sign in as in the form: ask the server, "browser" (here directly the redirect), complete. */
    private suspend fun signIn(serverId: String? = null): String {
        val started = servers.beginOpenId(baseUrl, AbsAuthApi.DEFAULT_REDIRECT_URI) as BeginOpenIdResult.Started
        val state = started.login.authorizationUrl.queryParameter("state")
        val result = servers.connectOpenId(serverId, "Audiobooks", started.login, "audiobookshelf://oauth?code=the-code&state=$state")
        return (result as AddServerResult.Added).serverId
    }

    @Test
    fun `status tells whether OpenID is offered`() = runTest {
        val options = servers.loginOptions(baseUrl)!!
        assertTrue(options.openId)
    }

    @Test
    fun `sign-in stores both tokens and marks the server as OpenID`() = runTest {
        val id = signIn()

        assertEquals(jwt("access-1"), secrets.values[AbsSecrets.token(id)])
        assertEquals("refresh-1", secrets.values[AbsSecrets.refresh(id)])
        assertTrue(db.bookDao().server(id)!!.openId)
        assertTrue(servers.editable(id)!!.openId)
        assertEquals(jwt("access-1"), registry[id]!!.token)
    }

    @Test
    fun `a redirect address the server does not allow is reported`() = runTest {
        val result = servers.beginOpenId(baseUrl, "earloft://oauth")
        assertEquals(BeginOpenIdResult.RedirectNotAllowed("earloft://oauth"), result)
    }

    @Test
    fun `a redirect with a foreign state is rejected`() = runTest {
        val started = servers.beginOpenId(baseUrl, AbsAuthApi.DEFAULT_REDIRECT_URI) as BeginOpenIdResult.Started
        val result = servers.connectOpenId(null, "x", started.login, "audiobookshelf://oauth?code=the-code&state=other")
        assertEquals(AddServerResult.OpenIdFailed, result)
        assertTrue(db.bookDao().servers().isEmpty())
    }

    @Test
    fun `an expired access token is renewed once for parallel requests`() = runTest {
        val id = signIn()
        validAccess.clear() // The access token has expired.

        val me = (1..4).map { async(Dispatchers.IO) { api.me(registry[id]!!) } }.awaitAll()

        assertEquals(4, me.size)
        assertEquals(1, refreshCalls.get())
        assertEquals(jwt("access-2"), secrets.values[AbsSecrets.token(id)])
        assertEquals("refresh-2", secrets.values[AbsSecrets.refresh(id)])
        assertEquals(jwt("access-2"), registry[id]!!.token)
    }

    @Test
    fun `streams, downloads and covers without their own header renew the token too`() = runTest {
        val id = signIn()
        servers.server(id)
        validAccess.clear() // The access token has expired, and no API call renewed it beforehand (review H3).

        val response = client.newCall(Request.Builder().url(server.url("/api/items/book/file/1")).build()).execute()

        response.use { assertEquals(200, it.code) }
        assertEquals(1, refreshCalls.get())
        assertEquals(jwt("access-2"), registry[id]!!.token)
    }

    @Test(timeout = 10_000)
    fun `an expired refresh token on a stream ends with 401 instead of hanging`() = runTest {
        val id = signIn()
        servers.server(id)
        validAccess.clear()
        currentRefresh = "revoked"

        val response = client.newCall(Request.Builder().url(server.url("/api/items/book/file/1")).build()).execute()

        response.use { assertEquals(401, it.code) }
        assertNull(registry[id])
    }

    @Test
    fun `a stale server object is retried with the renewed token`() = runTest {
        val id = signIn()
        val stale = registry[id]!!
        validAccess.clear()
        api.me(stale) // renews
        api.me(stale) // still runs with the old token: retried without a new renewal
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `an expired refresh token asks for a new sign-in`() = runTest {
        val id = signIn()
        validAccess.clear()
        currentRefresh = "revoked"

        val failed = runCatching { api.me(registry[id]!!) }

        assertTrue(failed.isFailure)
        assertNull(secrets.values[AbsSecrets.token(id)])
        assertNull(secrets.values[AbsSecrets.refresh(id)])
        assertNull(registry[id])
        assertEquals(setOf(id), servers.serversWithoutKey.first())
        assertEquals(listOf(id), servers.withoutKey())
    }

    @Test
    fun `a wrong new API key is not replaced by the stored token`() = runTest {
        val id = signIn()
        val result = servers.update(id, "Audiobooks", baseUrl, "wrong-key")
        assertEquals(AddServerResult.AuthFailed, result)
        assertEquals(0, refreshCalls.get())
        assertEquals(jwt("access-1"), secrets.values[AbsSecrets.token(id)])
    }

    @Test
    fun `saving without a key keeps the OpenID sign-in even when the access token expired`() = runTest {
        val id = signIn()
        validAccess.clear()

        val result = servers.update(id, "New", baseUrl, "")

        assertEquals(AddServerResult.Added(id), result)
        val entity = db.bookDao().server(id)!!
        assertTrue(entity.openId)
        assertEquals("New", entity.name)
        assertEquals(jwt("access-2"), secrets.values[AbsSecrets.token(id)])
        assertEquals(jwt("access-2"), registry[id]!!.token)
    }

    @Test
    fun `switching to an API key drops the refresh token`() = runTest {
        val id = signIn()
        validAccess += "api-key"

        servers.update(id, "Audiobooks", baseUrl, "api-key")

        assertFalse(db.bookDao().server(id)!!.openId)
        assertEquals("api-key", secrets.values[AbsSecrets.token(id)])
        assertNull(secrets.values[AbsSecrets.refresh(id)])
    }

    @Test
    fun `fresh renews an access token that expires soon`() = runTest {
        val id = signIn()
        servers.server(id) // loads the registry, otherwise the first call to freshToken would replace it
        val soon = System.currentTimeMillis() / 1000 + 60
        registry.register(registry[id]!!.copy(token = jwt("access-1", soon)))

        assertEquals(jwt("access-2"), servers.freshToken(id))
        assertEquals(jwt("access-2"), servers.freshToken(id))
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `a renewal the caller gives up on still stores the rotated tokens`() = runTest {
        val id = signIn()
        servers.server(id)
        registry.register(registry[id]!!.copy(token = jwt("access-1", System.currentTimeMillis() / 1000 + 60)))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        refreshGate = arrived to release

        val caller = launch(Dispatchers.IO) { renewal.fresh(id, 30 * 60) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        caller.cancel()
        release.countDown()
        caller.join()

        assertEquals("refresh-2", secrets.values[AbsSecrets.refresh(id)])
        assertEquals(jwt("access-2"), secrets.values[AbsSecrets.token(id)])
        assertEquals(jwt("access-2"), registry[id]!!.token)
    }

    @Test
    fun `the watch gets an access token that is still valid, never the refresh token`() = runTest {
        val id = signIn()
        servers.server(id)
        registry.register(registry[id]!!.copy(token = jwt("access-1", System.currentTimeMillis() / 1000 + 60)))

        val forWatch = servers.apiKey(id)

        assertEquals(jwt("access-2"), forWatch)
        assertFalse(forWatch == secrets.values[AbsSecrets.refresh(id)])
    }

    @Test
    fun `the server decides whether the token for the watch is still valid`() = runTest {
        val id = signIn()
        // The phone's clock is behind: according to the JWT the token is still valid, the server rejects it.
        validAccess.clear()

        assertEquals(jwt("access-2"), servers.apiKey(id))
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `the watch lists servers whose access token expires soon`() = runTest {
        val id = signIn()
        servers.server(id)
        assertTrue(servers.withExpiringToken(5 * 60).isEmpty())

        // On the watch: only the access token from the phone, no refresh token.
        secrets.values.remove(AbsSecrets.refresh(id))
        registry.register(registry[id]!!.copy(token = jwt("access-1", System.currentTimeMillis() / 1000 + 60)))
        assertEquals(listOf(id), servers.withExpiringToken(5 * 60))

        registry.register(registry[id]!!.copy(token = "api-key-without-expiry"))
        assertTrue(servers.withExpiringToken(5 * 60).isEmpty())
    }

    @Test
    fun `the watch stamp follows the sign-in, not the hourly access token`() = runTest {
        val id = signIn()
        val before = servers.mirrored.first().single().credentialsStamp
        validAccess.clear()
        api.me(registry[id]!!)
        assertEquals(before, servers.mirrored.first().single().credentialsStamp)

        signIn(id)
        assertFalse(before == servers.mirrored.first().single().credentialsStamp)
    }

    @Test
    fun `token expiry is read from the JWT`() {
        assertEquals(1_900_000_000L, AbsAuthApi.expiresAt(jwt("x", 1_900_000_000L)))
        assertNull(AbsAuthApi.expiresAt("not-a-jwt"))
    }

    private inner class FakeAudiobookshelf : Dispatcher() {
        private var accessCounter = 1

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
            return when (url.encodedPath) {
                "/status" -> json(
                    """{"app":"audiobookshelf","serverVersion":"2.37.2","authMethods":["openid"],""" +
                        """"authFormData":{"authOpenIDButtonText":"Login with OpenId"}}""",
                )
                "/auth/openid" -> {
                    if (url.queryParameter("redirect_uri") != AbsAuthApi.DEFAULT_REDIRECT_URI) {
                        MockResponse.Builder().code(400).body("Invalid redirect_uri").build()
                    } else {
                        challenge = url.queryParameter("code_challenge")
                        MockResponse.Builder().code(302)
                            .addHeader("Set-Cookie", "connect.sid=session-1; Path=/; HttpOnly")
                            .addHeader("Location", "https://idp.example/authorize?state=${url.queryParameter("state")}")
                            .build()
                    }
                }
                "/auth/openid/callback" -> {
                    val verifier = url.queryParameter("code_verifier").orEmpty()
                    val ok = request.headers["Cookie"]?.contains("connect.sid=session-1") == true &&
                        url.queryParameter("code") == "the-code" && s256(verifier) == challenge
                    if (!ok) MockResponse.Builder().code(401).body("Unauthorized").build() else synchronized(this) {
                        currentRefresh = "refresh-1"
                        tokens(jwt("access-1"), currentRefresh)
                    }
                }
                "/auth/refresh" -> {
                    refreshGate?.let { (arrived, release) ->
                        arrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    refresh(request)
                }
                "/ping" -> json("""{"success":true}""")
                else -> when {
                    bearer == null || bearer !in validAccess -> MockResponse.Builder().code(401).body("Unauthorized").build()
                    url.encodedPath == "/api/me" -> json("""{"id":"user-1","mediaProgress":[]}""")
                    url.encodedPath.startsWith("/api/items/") -> MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("audio").build()
                    url.encodedPath == "/api/libraries" -> json("""{"libraries":[{"id":"lib","name":"Books","mediaType":"book"}]}""")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        private fun refresh(request: RecordedRequest): MockResponse = synchronized(this) {
            refreshCalls.incrementAndGet()
            if (request.headers["x-refresh-token"] != currentRefresh) {
                MockResponse.Builder().code(401).body("""{"error":"Invalid refresh token"}""").build()
            } else {
                accessCounter++
                currentRefresh = "refresh-$accessCounter"
                tokens(jwt("access-$accessCounter"), currentRefresh)
            }
        }

        private fun tokens(access: String, refresh: String): MockResponse {
            validAccess += access
            return json("""{"user":{"id":"user-1","accessToken":"$access","refreshToken":"$refresh"}}""")
        }

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }

    private class MemorySecrets : SecretStore {
        val values = ConcurrentHashMap<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private companion object {
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        val inOneHour = System.currentTimeMillis() / 1000 + 3600

        /** A JWT as Audiobookshelf issues it (signature irrelevant). */
        fun jwt(id: String, exp: Long = inOneHour): String {
            val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
            val payload = encoder.encodeToString("""{"userId":"user-1","jti":"$id","exp":$exp}""".toByteArray())
            return "$header.$payload.sig"
        }

        fun s256(verifier: String): String =
            encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
