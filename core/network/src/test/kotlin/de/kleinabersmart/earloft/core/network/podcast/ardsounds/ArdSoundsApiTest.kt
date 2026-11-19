package de.kleinabersmart.earloft.core.network.podcast.ardsounds

import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedException
import de.kleinabersmart.earloft.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

class ArdSoundsApiTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var api: ArdSoundsApi

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        api = ArdSoundsApi(OkHttpClient(), mockServer.url("/graphql"))
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `search prefers a public rss feed and falls back to the show id`() = runTest {
        mockServer.enqueue(
            json(
                """{"data":{"search":{"programSets":{"nodes":[
                    {"id":"1","coreId":"urn:ard:show:aa","title":"With feed","numberOfElements":3,"feedUrl":"https://feeds.example.org/a.xml",
                     "image":{"url":"https://img.example/a?w={width}","url1X1":"https://img.example/a1?w={width}"},"publicationService":{"title":"Bayern 2"}},
                    {"id":"2","coreId":"urn:ard:show:bb","title":"Without feed","numberOfElements":9,"feedUrl":"crid://ndr.de/4532",
                     "image":{"url":"https://img.example/b?w={width}"},"publicationService":{"title":"NDR"}},
                    {"id":"3","coreId":null,"title":"Number only","numberOfElements":null,"feedUrl":null},
                    {"id":"4","coreId":"urn:ard:show:dd","title":"Empty","numberOfElements":0,"feedUrl":null}
                ]}}}}""",
            ),
        )

        val hits = api.search("tatort")

        assertEquals(
            listOf(
                DirectoryPodcast(
                    "With feed",
                    "Bayern 2",
                    "https://feeds.example.org/a.xml",
                    "https://img.example/a1?w=600",
                    setOf(PodcastDirectory.ARD_SOUNDS),
                    ardSoundsUrl = "ardsounds:urn:ard:show:aa",
                ),
                DirectoryPodcast("Without feed", "NDR", "ardsounds:urn:ard:show:bb", "https://img.example/b?w=600", setOf(PodcastDirectory.ARD_SOUNDS)),
                DirectoryPodcast("Number only", "", "ardsounds:3", null, setOf(PodcastDirectory.ARD_SOUNDS)),
            ),
            hits,
        )
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val variables = body.getValue("variables").jsonObject
        assertEquals("tatort", variables.getValue("query").jsonPrimitive.content)
        assertEquals(50, variables.getValue("limit").jsonPrimitive.int)
    }

    @Test
    fun `a show becomes a parsed feed with downloadable audio, dates and show notes`() = runTest {
        mockServer.enqueue(
            json(
                """{"data":{"show":{"id":"9","coreId":"urn:ard:show:cc","title":"Radio Tatort","description":"<p>Crime stories</p>","synopsis":"Crime stories, short",
                    "image":{"url1X1":"https://img.example/s?w={width}"},"publicationService":{"title":"ARD"},
                    "items":{"nodes":[
                      {"id":"11","coreId":"urn:ard:publication:x1","title":"Case 2","publishDate":"2026-10-09T10:03:49+02:00","duration":3460,
                       "description":"short","showNotes":"<b>long</b>","summary":"s","image":{"url1X1":"https://img.example/e?w={width}"},
                       "audios":[{"url":"https://stream.example/2.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"},
                                 {"url":"https://dl.example/2.mp3","downloadUrl":"https://dl.example/2.mp3","allowDownload":true,"mimeType":"audio/mp3"}]},
                      {"id":"12","coreId":null,"title":"Case 1","publishDate":"broken","duration":null,"description":null,"showNotes":null,"summary":"Summary",
                       "audios":[{"url":"https://stream.example/1.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp4"}]},
                      {"id":"13","coreId":"urn:ard:publication:x0","title":"No sound","audios":[]}
                    ]}}}}""",
            ),
        )

        val feed = api.fetch("urn:ard:show:cc")

        assertEquals("Radio Tatort", feed.title)
        assertEquals("ARD", feed.author)
        assertEquals("<p>Crime stories</p>", feed.description)
        assertEquals("https://img.example/s?w=600", feed.imageUrl)
        assertEquals(listOf("urn:ard:publication:x1", "12"), feed.episodes.map { it.guid })
        val newest = feed.episodes[0]
        assertEquals("https://dl.example/2.mp3", newest.audioUrl)
        assertEquals("audio/mpeg", newest.mimeType)
        assertEquals(3_460_000L, newest.durationMs)
        assertEquals(OffsetDateTime.parse("2026-10-09T10:03:49+02:00").toInstant().toEpochMilli(), newest.publishedAt)
        assertEquals("<b>long</b>", newest.description)
        assertEquals("https://img.example/e?w=600", newest.imageUrl)
        val older = feed.episodes[1]
        assertEquals("https://stream.example/1.mp3", older.audioUrl)
        assertEquals("audio/mp4", older.mimeType)
        assertEquals(0L, older.publishedAt)
        assertEquals(0L, older.durationMs)
        assertEquals("Summary", older.description)
        val variables = Json.parseToJsonElement(mockServer.takeRequest().body!!.utf8()).jsonObject.getValue("variables").jsonObject
        assertEquals("urn:ard:show:cc", variables.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `an unknown show is not a feed`() = runTest {
        mockServer.enqueue(json("""{"data":{"show":null}}"""))

        assertTrue(runCatching { api.fetch("urn:ard:show:gone") }.exceptionOrNull() is PodcastFeedException)
    }

    @Test
    fun `graphql errors, http errors and a changed answer are io errors`() = runTest {
        mockServer.enqueue(json("""{"errors":[{"message":"Cannot query field"}],"data":null}"""))
        mockServer.enqueue(MockResponse.Builder().code(502).build())
        mockServer.enqueue(json("""{"data":{"search":{"programSets":{"nodes":"anders"}}}}"""))
        mockServer.enqueue(json("<html>"))

        repeat(4) {
            val error = runCatching { api.search("x") }.exceptionOrNull()
            assertTrue("$error", error is IOException)
        }
    }

    @Test
    fun `show links and subscription addresses become subscription addresses`() {
        val expected = "ardsounds:urn:ard:show:22fd9a990b9bdd03"
        assertEquals(expected, parseArdSoundsInput("https://www.ardsounds.de/sendung/tatort-kunst/urn:ard:show:22fd9a990b9bdd03/"))
        assertEquals(expected, parseArdSoundsInput("ardaudiothek.de/sendung/tatort-kunst/urn:ard:show:22fd9a990b9bdd03"))
        assertEquals(expected, parseArdSoundsInput(" ardsounds:urn:ard:show:22fd9a990b9bdd03 "))
        assertNull(parseArdSoundsInput("https://www.ardsounds.de/episode/x/urn:ard:episode:1234/"))
        assertNull(parseArdSoundsInput("https://example.org/sendung/x/urn:ard:show:1234/"))
        assertNull(parseArdSoundsInput("https://feeds.br.de/feed.xml"))
        assertNull(parseArdSoundsInput("ardsounds:"))
        assertEquals("urn:ard:show:1", ardSoundsShowId("ardsounds:urn:ard:show:1"))
        assertNull(ardSoundsShowId("https://feeds.br.de/feed.xml"))
    }

    @Test
    fun `rss bridge feeds lead to the show and back`() {
        val bridged = ardSoundsBridgeUrl(DEFAULT_RSS_BRIDGE, "urn:ard:show:5fa94a3212aa9829")

        assertEquals(
            "https://rss-bridge.org/bridge01/?action=display&bridge=ARDAudiothekBridge&path=urn%3Aard%3Ashow%3A5fa94a3212aa9829&format=Atom",
            bridged,
        )
        assertEquals("ardsounds:urn:ard:show:5fa94a3212aa9829", parseArdSoundsInput(bridged!!))
        // Addresses of other instances and with the full link of the show page as path, as the bridge builds them itself.
        assertEquals(
            "ardsounds:urn:ard:show:5fa94a3212aa9829",
            parseArdSoundsInput(
                "https://rss.example/?action=display&bridge=ARDAudiothekBridge&path=https%3A%2F%2Fwww.ardsounds.de%2Fsendung%2F" +
                    "talk-ohne-gast%2Furn%3Aard%3Ashow%3A5fa94a3212aa9829%2F&limit=20&format=Mrss",
            ),
        )
        assertNull(parseArdSoundsInput("https://rss.example/?action=display&bridge=OtherBridge&path=urn:ard:show:5fa9"))
        // Only shows with a urn can be resolved via the bridge; no bridge without a valid address.
        assertNull(ardSoundsBridgeUrl(DEFAULT_RSS_BRIDGE, "12345"))
        assertNull(ardSoundsBridgeUrl("", "urn:ard:show:5fa9"))
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
}
