package de.kleinabersmart.earloft.core.network.podcast

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Feeds and chapters stay out of the shared HTTP cache, which is meant for covers and images (review2 J8). */
class PodcastFeedCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: Cache
    private lateinit var api: PodcastFeedApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cache = Cache(folder.newFolder("http"), 10L * 1024 * 1024)
        api = PodcastFeedApi(OkHttpClient.Builder().cache(cache).build())
    }

    @After
    fun tearDown() {
        server.close()
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
    fun `a fetched feed is not written to the HTTP cache`() = runTest {
        server.enqueue(cacheable("application/rss+xml", """<rss version="2.0"><channel><title>T</title></channel></rss>"""))

        api.fetch(server.url("/feed.xml"))

        assertEquals(0, cache.writeSuccessCount())
    }

    @Test
    fun `a feed is parsed as it arrives and one beyond the limit is rejected as truncated`() = runTest {
        // review5 P26: read straight from the network instead of buffering up to 20 MB per feed first.
        val items = (1..50).joinToString("") { "<item><title>Episode $it</title><guid>$it</guid><enclosure url=\"https://example.com/$it.mp3\" type=\"audio/mpeg\"/></item>" }
        val feed = """<rss version="2.0"><channel><title>T</title>$items</channel></rss>"""
        val small = PodcastFeedApi(OkHttpClient(), maxFeedBytes = feed.length.toLong())
        val tight = PodcastFeedApi(OkHttpClient(), maxFeedBytes = feed.length / 2L)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/rss+xml").body(feed).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/rss+xml").body(feed).build())

        val fetched = small.fetch(server.url("/feed.xml")) as FeedFetch.Fetched
        assertEquals(50, fetched.feed.episodes.size)
        assertThrows(PodcastFeedException::class.java) { runBlocking { tight.fetch(server.url("/feed.xml")) } }
    }

    @Test
    fun `chapters are not written to the HTTP cache`() = runTest {
        server.enqueue(cacheable("application/json", """{"version":"1.2.0","chapters":[{"startTime":0,"title":"A"}]}"""))

        api.chapters(server.url("/chapters.json").toString())

        assertEquals(0, cache.writeSuccessCount())
    }

    @Test
    fun `a transcript may still come from the HTTP cache`() = runTest {
        server.enqueue(cacheable("text/vtt", "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n"))

        api.transcript(server.url("/t.vtt").toString())

        // Guards the test itself: the server's headers do make a response cacheable.
        assertEquals(1, cache.writeSuccessCount())
    }
}
