package de.kleinabersmart.earloft.core.data.spoken

import android.app.Application
import de.kleinabersmart.earloft.core.data.podcast.MemorySecretStore
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renewal of an OpenID access token against a server that rotates the refresh token like Audiobookshelf: every refresh token
 * works once (review2 section 5, parallel 401).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AbsTokenRenewalTest {

    private val server = MockWebServer()
    private val secrets = MemorySecretStore()
    private val registry = AbsServerRegistry()
    private val renewal = AbsTokenRenewal(secrets, registry, AbsAuthApi(OkHttpClient()))

    /** The refresh token the server accepts next; each use replaces it. */
    @Volatile
    private var validRefresh = "refresh-1"
    private val refreshCalls = AtomicInteger()
    private var issued = 1

    @Volatile
    private var answer: (() -> MockResponse)? = null

    @Before
    fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/auth/refresh") return MockResponse.Builder().code(404).build()
                refreshCalls.incrementAndGet()
                // Long enough that the other callers queue up behind the running renewal.
                Thread.sleep(200)
                answer?.let { return it() }
                synchronized(this) {
                    if (request.headers["x-refresh-token"] != validRefresh) return MockResponse.Builder().code(401).body("Unauthorized").build()
                    issued++
                    validRefresh = "refresh-$issued"
                    return MockResponse.Builder()
                        .body("""{"user":{"accessToken":"access-$issued","refreshToken":"refresh-$issued"}}""")
                        .build()
                }
            }
        }
        server.start()
        registry.register(AbsServer(SERVER, server.url("/"), "access-1"))
        secrets.put(AbsSecrets.token(SERVER), "access-1")
        secrets.put(AbsSecrets.refresh(SERVER), "refresh-1")
        secrets.put(AbsSecrets.login(SERVER), "login")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `parallel 401 with the same token renew once and all retry with the new one`() {
        val tokens = rejectInParallel(times = 6, rejected = "access-1")

        assertEquals("one renewal, the rotated refresh token is used only once", 1, refreshCalls.get())
        assertEquals(List(6) { "access-2" }, tokens)
        assertEquals("access-2", registry[SERVER]?.token)
        assertEquals("refresh-2", secrets.values[AbsSecrets.refresh(SERVER)])
        assertEquals("access-2", secrets.values[AbsSecrets.token(SERVER)])
        assertEquals(0, renewal.expired.value)
    }

    @Test
    fun `a request that still carried a superseded token gets the current one without a renewal`() = runTest {
        assertEquals("access-2", renewal.renew(SERVER, "access-1"))
        assertEquals("access-3", renewal.renew(SERVER, "access-2"))

        assertEquals("access-3", renewal.renew(SERVER, "access-1"))
        assertEquals(2, refreshCalls.get())
    }

    @Test
    fun `a token the server never had is not renewed`() = runTest {
        assertNull(renewal.renew(SERVER, "someone-else"))
        assertNull(renewal.renew("unknown", "access-1"))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `a rejected refresh token ends the login once, the waiting callers get nothing`() {
        validRefresh = "already-rotated"

        val tokens = rejectInParallel(times = 4, rejected = "access-1")

        assertEquals(List<String?>(4) { null }, tokens)
        assertEquals("the others find the login gone instead of asking again", 1, refreshCalls.get())
        assertEquals(1, renewal.expired.value)
        assertNull(registry[SERVER])
        assertNull(secrets.values[AbsSecrets.token(SERVER)])
        assertNull(secrets.values[AbsSecrets.refresh(SERVER)])
        assertNull(secrets.values[AbsSecrets.login(SERVER)])
    }

    @Test
    fun `a server error keeps the login and fails like a network error, not as a rejection`() = runTest {
        answer = { MockResponse.Builder().code(503).build() }

        try {
            renewal.renew(SERVER, "access-1")
            fail("A temporary failure must not look like a rejected login")
        } catch (e: IOException) {
            assertFalse(e is AbsException)
        }

        assertEquals(0, renewal.expired.value)
        assertEquals("access-1", registry[SERVER]?.token)
        assertEquals("refresh-1", secrets.values[AbsSecrets.refresh(SERVER)])
    }

    /** Like OkHttp's authenticator: [times] requests rejected with [rejected] at the same moment, each on its own thread. */
    private fun rejectInParallel(times: Int, rejected: String): List<String?> {
        val pool = Executors.newFixedThreadPool(times)
        try {
            val start = CountDownLatch(1)
            val futures = List(times) {
                pool.submit(Callable { start.await(); renewal.renewBlocking(SERVER, rejected) })
            }
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val SERVER = "abs"
    }
}
