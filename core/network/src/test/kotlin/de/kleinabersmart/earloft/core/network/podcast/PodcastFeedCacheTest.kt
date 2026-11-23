package de.kleinabersmart.earloft.core.network.podcast

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
