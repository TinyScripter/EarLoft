package de.kleinabersmart.earloft.core.network.podcast.directory

import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class PodcastDirectoryApiTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var api: PodcastDirectoryApi

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        api = PodcastDirectoryApi(
            OkHttpClient(),
            DirectoryEndpoints(
                apple = mockServer.url("/search"),
                podcastIndex = mockServer.url("/api/1.0/search/byterm"),
                fyyd = mockServer.url("/0.2/search/podcast"),
                appleCharts = mockServer.url("/"),
                appleLookup = mockServer.url("/lookup"),
                podcastIndexTrending = mockServer.url("/api/1.0/podcasts/trending"),
            ),
            DirectoryAccess(podcastIndexKey = "KEY", podcastIndexSecret = "SECRET", fyydEnabled = true),
            ArdSoundsApi(OkHttpClient(), mockServer.url("/graphql")),
            clock = { 1_700_000_000_123 },
        )
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `apple results carry the feed and the large artwork, hits without feed are dropped`() = runTest {
        mockServer.enqueue(
            json(
                """{"resultCount":2,"results":[
                    {"collectionName":"Lage der Nation","artistName":"Banse & Buermeyer","feedUrl":"https://feeds.example.org/ldn.xml",
                     "artworkUrl100":"https://img.example/100.jpg","artworkUrl600":"https://img.example/600.jpg"},
                    {"collectionName":"Ohne Feed","artistName":"x"}
                ]}""",
            ),
        )

        val hits = api.search(PodcastDirectory.APPLE, "lage der nation", "DE")

        assertEquals(
            listOf(
                DirectoryPodcast(
                    "Lage der Nation",
                    "Banse & Buermeyer",
                    "https://feeds.example.org/ldn.xml",
                    "https://img.example/600.jpg",
                    setOf(PodcastDirectory.APPLE),
                ),
            ),
            hits,
        )
        val request = mockServer.takeRequest()
        assertEquals("lage der nation", request.url.queryParameter("term"))
        assertEquals("DE", request.url.queryParameter("country"))
        assertEquals("podcast", request.url.queryParameter("media"))
        assertEquals("EarLoft/1.0 (Android podcast client)", request.headers["User-Agent"])
    }

    @Test
    fun `podcast index requests are signed with key, secret and date`() = runTest {
        mockServer.enqueue(
            json("""{"status":"true","feeds":[{"title":"PI","author":"A","url":"http://pi.example/feed","image":"https://pi.example/i.png","artwork":""}]}"""),
        )

        val hits = api.search(PodcastDirectory.PODCAST_INDEX, "pi", "DE")

        assertEquals("http://pi.example/feed", hits.single().feedUrl)
        assertEquals("https://pi.example/i.png", hits.single().imageUrl)
        val request = mockServer.takeRequest()
        assertEquals("pi", request.url.queryParameter("q"))
        assertEquals("KEY", request.headers["X-Auth-Key"])
        assertEquals("1700000000", request.headers["X-Auth-Date"])
        assertEquals(sha1("KEYSECRET1700000000"), request.headers["Authorization"])
    }

    @Test
    fun `fyyd results use the small image`() = runTest {
        mockServer.enqueue(
            json(
                """{"status":1,"msg":"ok","data":[{"title":"Das Interview","author":"Philip Banse",
                    "xmlURL":"https:\/\/www.example.io\/feed\/mp3\/","imgURL":"https://big","smallImageURL":"https://small"}]}""",
            ),
        )

        val hit = api.search(PodcastDirectory.FYYD, "banse", "DE").single()

        assertEquals("https://www.example.io/feed/mp3/", hit.feedUrl)
        assertEquals("https://small", hit.imageUrl)
        assertEquals("banse", mockServer.takeRequest().url.queryParameter("term"))
    }

    @Test
    fun `apple charts of a genre keep their order and get their feeds from one lookup`() = runTest {
        mockServer.enqueue(
            json(
                """{"feed":{"title":{"label":"Top"},"entry":[
                    {"im:name":{"label":"Zwei"},"id":{"label":"https://podcasts.apple.com/de/podcast/id2","attributes":{"im:id":"2"}}},
                    {"im:name":{"label":"Eins"},"id":{"label":"https://podcasts.apple.com/de/podcast/id1","attributes":{"im:id":"1"}}},
                    {"im:name":{"label":"Ohne Feed"},"id":{"label":"x","attributes":{"im:id":"3"}}}
                ]}}""",
            ),
        )
        mockServer.enqueue(
            json(
                """{"resultCount":3,"results":[
                    {"collectionId":1,"collectionName":"Eins","artistName":"A","feedUrl":"https://one.example/feed","artworkUrl600":"https://img/1"},
                    {"collectionId":2,"collectionName":"Zwei","artistName":"B","feedUrl":"https://two.example/feed"},
                    {"collectionId":3,"collectionName":"Ohne Feed"}
                ]}""",
            ),
        )

        val hits = api.popular(PodcastDirectory.APPLE, PodcastCategory.NEWS, "DE", "de")

        assertEquals(listOf("https://two.example/feed", "https://one.example/feed"), hits.map { it.feedUrl })
        assertEquals("https://img/1", hits[1].imageUrl)
        assertEquals("/de/rss/toppodcasts/limit=50/genre=1489/json", mockServer.takeRequest().url.encodedPath)
        val lookup = mockServer.takeRequest().url
        assertEquals("2,1,3", lookup.queryParameter("id"))
        assertEquals("DE", lookup.queryParameter("country"))
    }

    @Test
    fun `apple charts without genre, with a single entry or none`() = runTest {
        mockServer.enqueue(json("""{"feed":{"entry":{"id":{"label":"x","attributes":{"im:id":"7"}}}}}"""))
        mockServer.enqueue(json("""{"results":[{"collectionId":7,"collectionName":"Sieben","feedUrl":"https://seven.example/feed"}]}"""))
        mockServer.enqueue(json("""{"feed":{"title":{"label":"Leer"}}}"""))

        assertEquals(listOf("Sieben"), api.popular(PodcastDirectory.APPLE, null, "AT", "de").map { it.title })
        assertEquals("/at/rss/toppodcasts/limit=50/json", mockServer.takeRequest().url.encodedPath)
        mockServer.takeRequest()
        // No entries, no second request.
        assertEquals(emptyList<DirectoryPodcast>(), api.popular(PodcastDirectory.APPLE, null, "AT", "de"))
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `podcast index trends are signed and filtered by language and category`() = runTest {
        mockServer.enqueue(json("""{"status":"true","feeds":[{"title":"Trend","author":"T","url":"https://trend.example/feed","artwork":"https://img/t"}]}"""))

        val hit = api.popular(PodcastDirectory.PODCAST_INDEX, PodcastCategory.HEALTH, "DE", "de").single()

        assertEquals("https://trend.example/feed" to "https://img/t", hit.feedUrl to hit.imageUrl)
        val request = mockServer.takeRequest()
        assertEquals("/api/1.0/podcasts/trending", request.url.encodedPath)
        assertEquals("de,de-de", request.url.queryParameter("lang"))
        assertEquals("Health,Fitness", request.url.queryParameter("cat"))
        assertEquals(sha1("KEYSECRET1700000000"), request.headers["Authorization"])
    }

    @Test
    fun `an unexpected chart form is an io error`() = runTest {
        mockServer.enqueue(json("""{"feed":[1,2]}"""))

        assertTrue(runCatching { api.popular(PodcastDirectory.APPLE, null, "DE", "de") }.exceptionOrNull() is IOException)
    }

    @Test
    fun `http errors and unreadable answers are io errors`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(503).build())
        mockServer.enqueue(json("<html>"))

        assertTrue(runCatching { api.search(PodcastDirectory.APPLE, "x", "DE") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { api.search(PodcastDirectory.FYYD, "x", "DE") }.exceptionOrNull() is IOException)
    }

    @Test
    fun `only directories the build unlocks are available`() {
        val locked = PodcastDirectoryApi(OkHttpClient(), DirectoryEndpoints(), DirectoryAccess("", "", fyydEnabled = false), ArdSoundsApi(OkHttpClient()), clock = { 0 })
        assertEquals(listOf(PodcastDirectory.APPLE, PodcastDirectory.ARD_SOUNDS), locked.available)
        assertEquals(PodcastDirectory.entries, api.available)
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
