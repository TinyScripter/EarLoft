package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.network.HttpStatusException
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

        val result = api.authenticate(server.baseUrl, "earloft-jf", "test", "geh\"eim")

        val request = mockServer.takeRequest()
        assertEquals("/jellyfin/Users/AuthenticateByName", request.url.encodedPath)
        assertEquals("""{"Username":"test","Pw":"geh\"eim"}""", request.body?.utf8())
        val header = request.headers["Authorization"]!!
        assertTrue(header.startsWith("MediaBrowser Client=\"EarLoft\""))
        assertTrue("DeviceId=\"earloft-jf\"" in header)
        assertFalse("Token=" in header)
        // Quotes in the device name are encoded, the header stays valid.
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

        // Media3 and downloads load without a tag: authenticated via the registry only after signing in.
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

        // IDs stored before the next sync without a tag keep working.
        val plain = api.imageUrl(server, "al1", 300)
        assertEquals("/jellyfin/Items/al1/Images/Primary", plain.encodedPath)
        assertNull(plain.queryParameter("tag"))
    }

    @Test
    fun `token does not follow a redirect to another host`() = runTest {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(json("""{"Id":"u1","Name":"test"}"""))
            // Same machine, different name and port: a foreign host to OkHttp.
            val target = other.url("/login").newBuilder().host("127.0.0.1").build()
            mockServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())

            api.me(server)

            assertTrue("Token=\"tok\"" in mockServer.takeRequest().headers["Authorization"]!!)
            assertNull(other.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `the password is not sent on to a redirect target`() = runTest {
        MockWebServer().use { other ->
            other.start()
            mockServer.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/login").toString()).build())

            try {
                api.authenticate(server.baseUrl, "dev", "test", "geheim")
                fail("HttpStatusException erwartet")
            } catch (e: HttpStatusException) {
                assertEquals(307, e.status)
            }

            assertEquals(0, other.requestCount)
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
    fun `emby answers without product name, jellyfin is no emby`() = runTest {
        // This is how Emby 4.10 answers: without ProductName.
        mockServer.enqueue(json("""{"ServerName":"emby","Version":"4.10.2.0","Id":"e4"}"""))
        assertEquals("4.10.2.0", api.publicInfo(server.baseUrl, JellyfinFlavor.EMBY).version)

        mockServer.enqueue(json("""{"ProductName":"Jellyfin Server","Id":"j","Version":"12.4"}"""))
        try {
            api.publicInfo(server.baseUrl, JellyfinFlavor.EMBY)
            fail("NotJellyfinException erwartet")
        } catch (e: NotJellyfinException) {
            assertTrue("Emby" in e.message!!)
        }
    }

    @Test
    fun `emby uses the paths below the user`() = runTest {
        repeat(4) { mockServer.enqueue(json("""{"Id":"u1","Name":"test","Items":[]}""")) }

        api.me(emby)
        api.musicLibraries(emby)
        api.setFavorite(emby, "s1", favorite = false)
        api.markPlayed(emby, "s1", playedAt = 1_794_124_800_000) // 2026-11-08T08:00:00Z

        assertEquals("/emby/Users/u1", mockServer.takeRequest().url.encodedPath)
        assertEquals("/emby/Users/u1/Views", mockServer.takeRequest().url.encodedPath)
        val favorite = mockServer.takeRequest()
        assertEquals("DELETE", favorite.method)
        assertEquals("/emby/Users/u1/FavoriteItems/s1", favorite.url.encodedPath)
        val played = mockServer.takeRequest().url
        assertEquals("/emby/Users/u1/PlayedItems/s1", played.encodedPath)
        // Emby rejects ISO-8601 (measured on server 4.10).
        assertEquals("20261108080000", played.queryParameter("datePlayed"))
    }

    @Test
    fun `cast urls carry the token in the parameter of each server`() {
        assertEquals("tok", api.withToken(server, api.streamUrl(server, "s1")).queryParameter("ApiKey"))
        val embyUrl = api.withToken(emby, api.streamUrl(emby, "s1"))
        assertEquals("tok", embyUrl.queryParameter("api_key"))
        assertNull(embyUrl.queryParameter("ApiKey"))
    }

    @Test
    fun `emby playlists are created with query parameters in pieces`() = runTest {
        val ids = (1..JellyfinApi.EMBY_IDS_PER_REQUEST + 1).map { "s$it" }
        mockServer.enqueue(json("""{"Id":"p1","ItemAddedCount":200}"""))
        mockServer.enqueue(json("""{"Id":"p1","ItemAddedCount":1}"""))

        assertEquals("p1", api.createPlaylist(emby, "Sommer", ids))

        val create = mockServer.takeRequest().url
        assertEquals("/emby/Playlists", create.encodedPath)
        assertEquals("Sommer", create.queryParameter("name"))
        assertEquals("Audio", create.queryParameter("mediaType"))
        assertEquals(ids.dropLast(1).joinToString(","), create.queryParameter("ids"))
        val add = mockServer.takeRequest().url
        assertEquals("/emby/Playlists/p1/Items", add.encodedPath)
        assertEquals(ids.last(), add.queryParameter("ids"))
    }

    @Test
    fun `emby playlist update renames the whole item and keeps the common start`() = runTest {
        mockServer.enqueue(json("""{"Id":"p1","Name":"Sommer","Type":"Playlist","Etag":"e"}"""))
        mockServer.enqueue(MockResponse.Builder().code(204).build())
        mockServer.enqueue(
            json("""{"Items":[{"Id":"a","PlaylistItemId":"1"},{"Id":"b","PlaylistItemId":"2"},{"Id":"c","PlaylistItemId":"3"}]}"""),
        )
        mockServer.enqueue(MockResponse.Builder().code(204).build())
        mockServer.enqueue(json("""{"Id":"p1","ItemAddedCount":1}"""))

        api.updatePlaylist(emby, "p1", "Sommer 2", listOf("a", "c"))

        assertEquals("/emby/Users/u1/Items/p1", mockServer.takeRequest().url.encodedPath)
        val rename = mockServer.takeRequest()
        assertEquals("/emby/Items/p1", rename.url.encodedPath)
        // The whole entry goes back, otherwise Emby answers with 400.
        assertEquals("""{"Id":"p1","Name":"Sommer 2","Type":"Playlist","Etag":"e"}""", rename.body?.utf8())
        assertEquals("/emby/Playlists/p1/Items", mockServer.takeRequest().url.encodedPath)
        val remove = mockServer.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("2,3", remove.url.queryParameter("entryIds"))
        assertEquals("c", mockServer.takeRequest().url.queryParameter("ids"))
    }

    @Test
    fun `emby lyrics come from the lrc file, else from the tag`() = runTest {
        mockServer.enqueue(json(embySong(""",{"Type":"Subtitle","Codec":"text","Index":2,"Extradata":"Getaggt"},""" +
            """{"Type":"Subtitle","Codec":"lrc","Index":3,"IsExternal":true}""")))
        mockServer.enqueue(MockResponse.Builder().body("\uFEFF[00:05.01]Zeile").build())
        mockServer.enqueue(json(embySong(""",{"Type":"Subtitle","Codec":"text","Index":2,"Extradata":" "},""" +
            """{"Type":"Subtitle","Codec":"text","Index":3,"Extradata":"Nur Text"}""")))
        mockServer.enqueue(json(embySong("")))

        assertEquals("[00:05.01]Zeile", api.embyLyricsText(emby, "s1"))
        assertEquals("/emby/Users/u1/Items/s1", mockServer.takeRequest().url.encodedPath)
        assertEquals("/emby/Items/s1/ms1/Subtitles/3/Stream.lrc", mockServer.takeRequest().url.encodedPath)
        assertEquals("Nur Text", api.embyLyricsText(emby, "s1"))
        assertNull(api.embyLyricsText(emby, "s1"))
    }

    private val emby get() = server.copy(sourceId = "em", baseUrl = mockServer.url("/emby/"), flavor = JellyfinFlavor.EMBY)

    private fun embySong(moreStreams: String) =
        """{"Id":"s1","Type":"Audio","MediaSources":[{"Id":"ms1","MediaStreams":[{"Type":"Audio","Codec":"mp3","Index":0}$moreStreams]}]}"""

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
    fun `a playback is reported as started and stopped at the end`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(204).build())
        mockServer.enqueue(MockResponse.Builder().code(204).build())

        api.reportPlayback(server, "s1", durationMs = 118_000)

        val start = mockServer.takeRequest()
        assertEquals("/jellyfin/Sessions/Playing", start.url.encodedPath)
        val session = Regex(""""PlaySessionId":"([^"]+)"""").find(start.body!!.utf8())!!.groupValues[1]
        assertEquals("""{"ItemId":"s1","PositionTicks":0,"PlaySessionId":"$session","PlayMethod":"DirectPlay"}""", start.body?.utf8())
        val stop = mockServer.takeRequest()
        assertEquals("/jellyfin/Sessions/Playing/Stopped", stop.url.encodedPath)
        // Start and stop belong to the same session (Emby requires them).
        assertEquals(
            """{"ItemId":"s1","PositionTicks":1180000000,"PlaySessionId":"$session","PlayMethod":"DirectPlay"}""",
            stop.body?.utf8(),
        )
        assertTrue("Token=\"tok\"" in stop.headers["Authorization"]!!)
    }

    @Test
    fun `a playback whose start arrived counts as reported even if the end fails`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(204).build())
        mockServer.enqueue(MockResponse.Builder().code(500).build())

        // No exception: the server already counts at start; reported afterwards, it counted twice.
        api.reportPlayback(server, "s1", durationMs = 118_000)

        assertEquals(2, mockServer.requestCount)
    }

    @Test
    fun `a playback whose start failed is not reported`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(503).build())

        try {
            api.reportPlayback(server, "s1", durationMs = 118_000)
            fail("HttpStatusException erwartet")
        } catch (_: HttpStatusException) {
        }
        assertEquals(1, mockServer.requestCount)
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
