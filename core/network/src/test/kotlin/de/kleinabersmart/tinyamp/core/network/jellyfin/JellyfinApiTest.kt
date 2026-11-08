package de.kleinabersmart.tinyamp.core.network.jellyfin

import de.kleinabersmart.tinyamp.core.network.HttpStatusException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JellyfinApiTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var registry: JellyfinServerRegistry
    private lateinit var client: OkHttpClient
    private lateinit var api: JellyfinApi
    private lateinit var server: JellyfinServer
    private val clientInfo = JellyfinClientInfo(deviceName = "Pixel 10 \"Pro\"", version = "1.0")

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        registry = JellyfinServerRegistry()
        client = OkHttpClient.Builder().addNetworkInterceptor(JellyfinAuthInterceptor(registry, clientInfo)).build()
        api = JellyfinApi(client, clientInfo)
        server = JellyfinServer("jf", mockServer.url("/jellyfin/"), userId = "u1", token = "tok")
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `login sends the device without token and returns user and token`() = runTest {
        mockServer.enqueue(json("""{"AccessToken":"abc","User":{"Id":"u1","Name":"test"},"ServerId":"s"}"""))

        val result = api.authenticate(server.baseUrl, "tinyamp-jf", "test", "geh\"eim")

        val request = mockServer.takeRequest()
        assertEquals("/jellyfin/Users/AuthenticateByName", request.url.encodedPath)
        assertEquals("""{"Username":"test","Pw":"geh\"eim"}""", request.body?.utf8())
        val header = request.headers["Authorization"]!!
        assertTrue(header.startsWith("MediaBrowser Client=\"TinyAmp\""))
        assertTrue("DeviceId=\"tinyamp-jf\"" in header)
        assertFalse("Token=" in header)
        // Anführungszeichen im Gerätenamen sind kodiert, die Kopfzeile bleibt gültig.
        assertTrue("Device=\"Pixel+10+%22Pro%22\"" in header)
        assertEquals("abc", result.accessToken)
        assertEquals("u1", result.user.id)
    }

    @Test
    fun `api calls and streams of known servers carry the token, others do not`() = runTest {
        mockServer.enqueue(json("""{"Id":"u1","Name":"test"}"""))
        mockServer.enqueue(MockResponse.Builder().body("audio").build())
        mockServer.enqueue(MockResponse.Builder().body("audio").build())

        api.me(server)
        assertTrue("Token=\"tok\"" in mockServer.takeRequest().headers["Authorization"]!!)

        // Media3 und Downloads laden ohne Tag: erst nach dem Anmelden in der Registry authentifiziert.
        client.newCall(Request.Builder().url(api.streamUrl(server, "s1")).build()).execute().close()
        assertNull(mockServer.takeRequest().headers["Authorization"])
        registry.replaceAll(listOf(server))
        client.newCall(Request.Builder().url(api.streamUrl(server, "s1")).build()).execute().close()
        val stream = mockServer.takeRequest()
        assertTrue("Token=\"tok\"" in stream.headers["Authorization"]!!)
        assertEquals("/jellyfin/Audio/s1/stream", stream.url.encodedPath)
        assertEquals("true", stream.url.queryParameter("static"))
        assertNull(stream.url.queryParameter("ApiKey"))
    }

    @Test
    fun `cover url carries the image tag so a new cover gets a new url`() {
        val tagged = api.imageUrl(server, JellyfinCover.id("al1", "t2"), 300)
        assertEquals("/jellyfin/Items/al1/Images/Primary", tagged.encodedPath)
        assertEquals("t2", tagged.queryParameter("tag"))

        // Vor dem nächsten Abgleich gespeicherte IDs ohne Tag gehen weiter.
        val plain = api.imageUrl(server, "al1", 300)
        assertEquals("/jellyfin/Items/al1/Images/Primary", plain.encodedPath)
        assertNull(plain.queryParameter("tag"))
    }

    @Test
    fun `token does not follow a redirect to another host`() = runTest {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(json("""{"Id":"u1","Name":"test"}"""))
            // Gleicher Rechner, anderer Name und Port: für OkHttp ein fremder Host.
            val target = other.url("/login").newBuilder().host("127.0.0.1").build()
            mockServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())

            api.me(server)

            assertTrue("Token=\"tok\"" in mockServer.takeRequest().headers["Authorization"]!!)
            assertNull(other.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `rejected token is an auth error, foreign answers are not jellyfin`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(401).build())
        try {
            api.me(server)
            fail("HttpStatusException erwartet")
        } catch (e: HttpStatusException) {
            assertTrue(e.isAuthError)
        }

        mockServer.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>Navidrome</html>").build())
        try {
            api.publicInfo(server.baseUrl)
            fail("NotJellyfinException erwartet")
        } catch (_: NotJellyfinException) {
        }

        mockServer.enqueue(json("""{"ProductName":"Emby Server","Id":"x","Version":"4.8"}"""))
        try {
            api.publicInfo(server.baseUrl)
            fail("NotJellyfinException erwartet")
        } catch (_: NotJellyfinException) {
        }
    }

    @Test
    fun `missing lyrics are null, songs page asks for the needed fields`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(404).build())
        mockServer.enqueue(json("""{"Items":[{"Id":"s1","Type":"Audio","Name":"Leise"}],"TotalRecordCount":1}"""))

        assertNull(api.lyrics(server, "s1"))
        mockServer.takeRequest()
        val page = api.songs(server, "lib", startIndex = 500, limit = 500)

        assertEquals(listOf("s1"), page.items.map { it.id })
        val url = mockServer.takeRequest().url
        assertEquals("u1", url.queryParameter("userId"))
        assertEquals("lib", url.queryParameter("parentId"))
        assertEquals("Audio", url.queryParameter("includeItemTypes"))
        assertEquals("500", url.queryParameter("startIndex"))
        assertTrue("MediaSources" in url.queryParameter("fields")!!)
        assertTrue(url.queryParameter("sortBy")!!.startsWith("DateCreated"))
    }

    @Test
    fun `newest songs stay in the given library`() = runTest {
        mockServer.enqueue(json("""{"Items":[],"TotalRecordCount":0}"""))

        api.newestSongs(server, "lib", startIndex = 0, limit = 50)

        val url = mockServer.takeRequest().url
        assertEquals("lib", url.queryParameter("parentId"))
        assertEquals("DateCreated", url.queryParameter("sortBy"))
        assertEquals("Descending", url.queryParameter("sortOrder"))
    }

    @Test
    fun `playlists are created private and replaced in one call`() = runTest {
        mockServer.enqueue(json("""{"Id":"p1"}"""))
        mockServer.enqueue(MockResponse.Builder().code(204).build())

        assertEquals("p1", api.createPlaylist(server, "Sommer", listOf("a", "b")))
        api.updatePlaylist(server, "p1", "Sommer 2", listOf("b", "b"))

        val create = mockServer.takeRequest()
        assertEquals("/jellyfin/Playlists", create.url.encodedPath)
        assertEquals(
            """{"Name":"Sommer","Ids":["a","b"],"UserId":"u1","MediaType":"Audio","IsPublic":false}""",
            create.body?.utf8(),
        )
        val update = mockServer.takeRequest()
        assertEquals("/jellyfin/Playlists/p1", update.url.encodedPath)
        assertEquals("""{"Name":"Sommer 2","Ids":["b","b"]}""", update.body?.utf8())
    }

    @Test
    fun `transcoded stream names container, codec and bitrate in bit per second`() {
        val opus = api.transcodedStreamUrl(server, "s1", "opus", 128)
        assertEquals("ogg", opus.queryParameter("container"))
        assertEquals("opus", opus.queryParameter("audioCodec"))
        assertEquals("128000", opus.queryParameter("audioBitRate"))
        assertNull(opus.queryParameter("static"))
        assertEquals("mp3", api.transcodedStreamUrl(server, "s1", "mp3", 320).queryParameter("container"))
    }

    @Test
    fun `base url drops the web interface and keeps a sub path`() {
        assertEquals(
            "https://jellyfin.example.de/",
            JellyfinServer.normalizeBaseUrl("jellyfin.example.de/web/#/home.html")?.toString(),
        )
        assertEquals(
            "http://nas:8096/jellyfin/",
            JellyfinServer.normalizeBaseUrl("http://nas:8096/jellyfin/web/index.html")?.toString(),
        )
        assertNull(JellyfinServer.normalizeBaseUrl("ftp://nas"))
    }

    @Test
    fun `registry picks the server with the longest base path`() {
        val root = JellyfinServer("a", mockServer.url("/"), "u", "t1")
        registry.replaceAll(listOf(root, server))

        assertEquals("jf", registry.forUrl(mockServer.url("/jellyfin/Audio/1/stream"))?.sourceId)
        assertEquals("a", registry.forUrl(mockServer.url("/Audio/1/stream"))?.sourceId)
    }

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
}
