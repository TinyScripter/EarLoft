package de.kleinabersmart.earloft.core.network.podcast

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Protected feeds (concept 12.10): basic auth only on the feed's host. */
class FeedAuthTest {

    private lateinit var feedServer: MockWebServer
    private lateinit var otherServer: MockWebServer
    private val registry = FeedAuthRegistry()
    private val client = OkHttpClient.Builder().addNetworkInterceptor(FeedAuthInterceptor(registry)).build()
    private val api = PodcastFeedApi(client)

    @Before
    fun setUp() {
        feedServer = MockWebServer().apply { start() }
        otherServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        feedServer.close()
        otherServer.close()
    }

    private val basic = Credentials.basic("anna", "secret:ö", Charsets.UTF_8)

    private fun feed() = MockResponse.Builder()
        .addHeader("Content-Type", "application/rss+xml")
        .body("""<rss version="2.0"><channel><title>T</title></channel></rss>""")
        .build()

    @Test
    fun `the feed request carries the given credentials`() = runTest {
        feedServer.enqueue(feed())

        api.fetch(feedServer.url("/feed.xml"), auth = FeedAuth(feedServer.url("/feed.xml"), "anna", "secret:ö"))

        assertEquals(basic, feedServer.takeRequest().headers["Authorization"])
    }

    @Test
    fun `credentials do not follow a redirect to another host`() = runTest {
        feedServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", otherServer.url("/feed.xml").toString()).build())
        otherServer.enqueue(feed())

        api.fetch(feedServer.url("/feed.xml"), auth = FeedAuth(feedServer.url("/feed.xml"), "anna", "secret:ö"))

        assertEquals(basic, feedServer.takeRequest().headers["Authorization"])
        assertNull(otherServer.takeRequest().headers["Authorization"])
    }

    @Test
    fun `stored credentials sign audio on the feed host only`() {
        registry.replaceAll(mapOf("feed-1" to FeedAuth(feedServer.url("/feed.xml"), "anna", "secret:ö")))
        feedServer.enqueue(MockResponse.Builder().body("audio").build())
        otherServer.enqueue(MockResponse.Builder().body("audio").build())

        client.newCall(api.audioRequest(feedServer.url("/media/1.mp3").toString())!!).execute().close()
        client.newCall(api.audioRequest(otherServer.url("/media/1.mp3").toString())!!).execute().close()

        assertEquals(basic, feedServer.takeRequest().headers["Authorization"])
        assertNull(otherServer.takeRequest().headers["Authorization"])
    }

    @Test
    fun `two accounts on one host get the one whose feed lies next to the file`() {
        registry.replaceAll(
            mapOf(
                "feed-1" to FeedAuth(feedServer.url("/anna/feed.xml"), "anna", "a"),
                "feed-2" to FeedAuth(feedServer.url("/bert/feed.xml"), "bert", "b"),
            ),
        )

        assertEquals("bert", registry.forUrl(feedServer.url("/bert/media/1.mp3"))?.username)
        assertEquals("anna", registry.forUrl(feedServer.url("/anna/cover.jpg"))?.username)
        // No path matches: the first one, as before.
        assertEquals("anna", registry.forUrl(feedServer.url("/media/1.mp3"))?.username)
    }

    @Test
    fun `an existing authorization stays`() {
        registry.replaceAll(mapOf("feed-1" to FeedAuth(feedServer.url("/"), "anna", "secret:ö")))
        feedServer.enqueue(MockResponse.Builder().body("ok").build())

        client.newCall(Request.Builder().url(feedServer.url("/api/x")).header("Authorization", "Bearer abc").build()).execute().close()

        assertEquals("Bearer abc", feedServer.takeRequest().headers["Authorization"])
    }

    @Test
    fun `credentials in the address are split off`() {
        val (url, auth) = "https://anna:secret%3A%C3%B6@feeds.example.org/p.xml".toHttpUrl().splitCredentials()

        assertEquals("https://feeds.example.org/p.xml", url.toString())
        assertEquals(FeedAuth(url, "anna", "secret:ö"), auth)
        assertNull("https://feeds.example.org/p.xml".toHttpUrl().splitCredentials().second)
    }

    @Test
    fun `no credentials from https to http`() {
        val auth = FeedAuth("https://feeds.example.org/p.xml".toHttpUrl(), "anna", "x")

        assertEquals(false, auth.appliesTo("http://feeds.example.org/a.mp3".toHttpUrl()))
        assertEquals(true, auth.appliesTo("https://feeds.example.org/media/a.mp3".toHttpUrl()))
    }
}
