package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.network.HttpStatusException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Feed move (concept 12.10): permanent redirects and `itunes:new-feed-url`. */
class PodcastFeedMoveTest {

    private lateinit var server: MockWebServer
    private val api = PodcastFeedApi(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun redirect(code: Int, to: String) = MockResponse.Builder().code(code).addHeader("Location", to).build()

    private fun feed(extra: String = "") = MockResponse.Builder()
        .addHeader("Content-Type", "application/rss+xml")
        .body("""<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>T</title>$extra</channel></rss>""")
        .build()

    @Test
    fun `permanent redirects all the way give the new address`() = runTest {
        server.enqueue(redirect(301, "/b.xml"))
        server.enqueue(redirect(308, "/c.xml"))
        server.enqueue(feed())

        val fetched = api.fetch(server.url("/a.xml"))

        assertEquals(server.url("/c.xml").toString(), fetched.movedTo)
    }

    @Test
    fun `a temporary redirect ends the move`() = runTest {
        server.enqueue(redirect(301, "/b.xml"))
        server.enqueue(redirect(302, "/c.xml"))
        server.enqueue(feed())

        assertEquals(server.url("/b.xml").toString(), api.fetch(server.url("/a.xml")).movedTo)
        server.enqueue(redirect(307, "/b.xml"))
        server.enqueue(feed())
        assertNull(api.fetch(server.url("/a.xml")).movedTo)
    }

    @Test
    fun `a moved but unchanged feed still names its new address`() = runTest {
        server.enqueue(redirect(301, "/b.xml"))
        server.enqueue(MockResponse.Builder().code(304).build())

        val fetched = api.fetch(server.url("/a.xml"), etag = "\"v1\"")

        assertEquals(FeedFetch.NotModified(server.url("/b.xml").toString()), fetched)
    }

    @Test
    fun `the feed may announce its new address itself`() = runTest {
        server.enqueue(feed("<itunes:new-feed-url> https://new.example/feed.xml </itunes:new-feed-url>"))

        val fetched = api.fetch(server.url("/a.xml")) as FeedFetch.Fetched

        assertEquals("https://new.example/feed.xml", fetched.feed.newFeedUrl)
        assertNull(fetched.movedTo)
    }

    @Test
    fun `http errors keep their status`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val error = assertThrows(HttpStatusException::class.java) { kotlinx.coroutines.runBlocking { api.fetch(server.url("/a.xml")) } }

        assertEquals(404, error.status)
    }

    @Test
    fun `a move from https to http is not taken`() {
        val secure = "https://a.example/feed.xml".toHttpUrl()
        fun response(url: String, code: Int, prior: Response? = null) = Response.Builder()
            .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .priorResponse(prior).build()
        val first = response("https://a.example/feed.xml", 301)
        val last = response("http://b.example/feed.xml", 200, first)

        assertNull(permanentTarget(secure, last))
        assertEquals("https://b.example/feed.xml", permanentTarget(secure, response("https://b.example/feed.xml", 200, first)).toString())
    }
}
