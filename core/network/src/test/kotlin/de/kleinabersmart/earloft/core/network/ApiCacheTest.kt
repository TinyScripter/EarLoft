package de.kleinabersmart.earloft.core.network

import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinClientInfo
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * API answers of Subsonic, Jellyfin and Audiobookshelf stay out of the shared HTTP cache, which is meant for covers (review5 P6):
 * a full sync wrote many MB of JSON into it, and `GET /api/me` left the Audiobookshelf token on disk in plain text.
 */
class ApiCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var mockServer: MockWebServer
    private lateinit var cache: Cache
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        cache = Cache(folder.newFolder("http"), 10L * 1024 * 1024)
        client = OkHttpClient.Builder().cache(cache).build()
    }

    @After
    fun tearDown() {
        mockServer.close()
        cache.close()
    }

    /** A response OkHttp would gladly keep: cacheable for an hour, with a validator. */
    private fun cacheable(contentType: String, body: String) = MockResponse.Builder()
        .addHeader("Content-Type", contentType)
        .addHeader("Cache-Control", "public, max-age=3600")
        .addHeader("ETag", "\"v1\"")
        .body(body)
        .build()

    @Test
    fun `a Subsonic answer is not written to the HTTP cache`() = runTest {
        val ping = javaClass.classLoader!!.getResource("subsonic/ping.json")!!.readText()
        mockServer.enqueue(cacheable("application/json", ping))
        mockServer.enqueue(cacheable("application/json", ping))
        val server = SubsonicServer("nd", mockServer.url("/"), SubsonicCredentials.Password("kal", "secret"))

        SubsonicApi(client).ping(server)
        SubsonicApi(client).ping(server)

        assertEquals(0, cache.writeSuccessCount())
        // The second call went to the network instead of being answered from the cache.
        assertEquals(2, mockServer.requestCount)
    }

    @Test
    fun `a Jellyfin answer is not written to the HTTP cache`() = runTest {
        val me = """{"Id":"u1","Name":"test"}"""
        mockServer.enqueue(cacheable("application/json", me))
        mockServer.enqueue(cacheable("application/json", me))
        val api = JellyfinApi(client, JellyfinClientInfo(deviceName = "Pixel", version = "1.0"))
        val server = JellyfinServer("jf", mockServer.url("/"), userId = "u1", token = "tok")

        api.me(server)
        api.me(server)

        assertEquals(0, cache.writeSuccessCount())
        assertEquals(2, mockServer.requestCount)
    }

    @Test
    fun `an Audiobookshelf answer is not written to the HTTP cache, a cover still may be`() = runTest {
        mockServer.enqueue(cacheable("application/json", """{"success":true}"""))
        mockServer.enqueue(cacheable("image/jpeg", "jpeg"))
        val api = AbsApi(client)
        val server = AbsServer("abs", mockServer.url("/"), "token")

        api.ping(server)

        assertEquals(0, cache.writeSuccessCount())

        // Guards the test itself: the server's headers do make a response cacheable.
        assertNotNull(api.authorImage(server, "author", 100))
        assertEquals(1, cache.writeSuccessCount())
    }
}
