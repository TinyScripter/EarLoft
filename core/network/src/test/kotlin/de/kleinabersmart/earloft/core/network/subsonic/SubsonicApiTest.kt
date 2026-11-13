package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.model.FavoriteKind
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubsonicApiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockServer: MockWebServer
    private lateinit var registry: SubsonicServerRegistry
    private lateinit var client: OkHttpClient
    private lateinit var api: SubsonicApi
    private lateinit var server: SubsonicServer

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        registry = SubsonicServerRegistry()
        client = OkHttpClient.Builder()
            .cache(Cache(tempFolder.newFolder("http"), 10L * 1024 * 1024))
            .addNetworkInterceptor(SubsonicAuthInterceptor(registry))
            .addNetworkInterceptor(CoverArtCacheInterceptor())
            .build()
        api = SubsonicApi(client)
        server = SubsonicServer("nd", mockServer.url("/navidrome/"), SubsonicCredentials.Password("kal", "geheim"))
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `token auth uses md5 of password and salt`() = runTest {
        mockServer.enqueue(json("ping"))

        api.ping(server)

        val url = mockServer.takeRequest().url
        assertEquals("/navidrome/rest/ping", url.encodedPath)
        assertEquals("kal", url.queryParameter("u"))
        val salt = url.queryParameter("s")!!
        assertEquals(SubsonicAuthInterceptor.md5Hex("geheim$salt"), url.queryParameter("t"))
        assertNull(url.queryParameter("p"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("EarLoft", url.queryParameter("c"))
    }

    @Test
    fun `credentials do not follow a redirect to another host`() = runTest {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(json("ping"))
            // Gleicher Rechner, anderer Name und Port: für OkHttp ein fremder Host.
            val target = other.url("/navidrome/rest/ping").newBuilder().host("127.0.0.1").build()
            mockServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())

            try {
                api.ping(server)
            } catch (_: Exception) {
                // Ohne Zugangsdaten muss der Ping nicht gelingen; hier zählt nur, was beim fremden Host ankommt.
            }

            assertEquals("kal", mockServer.takeRequest().url.queryParameter("u"))
            val forwarded = other.takeRequest().url
            assertNull(forwarded.queryParameter("u"))
            assertNull(forwarded.queryParameter("t"))
        }
    }

    @Test
    fun `api key replaces username and token`() = runTest {
        mockServer.enqueue(json("ping"))

        api.ping(server.copy(credentials = SubsonicCredentials.ApiKey("abc")))

        val url = mockServer.takeRequest().url
        assertEquals("abc", url.queryParameter("apiKey"))
        assertNull(url.queryParameter("u"))
        assertNull(url.queryParameter("t"))
    }

    @Test
    fun `requests from other clients are authenticated only for registered servers`() {
        mockServer.enqueue(MockResponse.Builder().body("x").build())
        mockServer.enqueue(MockResponse.Builder().body("x").build())

        client.newCall(Request.Builder().url(api.streamUrl(server, "42")).build()).execute().close()
        assertNull(mockServer.takeRequest().url.queryParameter("u"))

        registry.register(server)
        client.newCall(Request.Builder().url(api.streamUrl(server, "42")).build()).execute().close()
        val url = mockServer.takeRequest().url
        assertEquals("kal", url.queryParameter("u"))
        assertEquals("42", url.queryParameter("id"))
        assertEquals("raw", url.queryParameter("format"))
    }

    @Test
    fun `transcoded stream asks for format and highest bitrate`() {
        val url = api.transcodedStreamUrl(server, "42", "opus", 128)

        assertEquals("/navidrome/rest/stream", url.encodedPath)
        assertEquals("42", url.queryParameter("id"))
        assertEquals(listOf("opus"), url.queryParameterValues("format"))
        assertEquals("128", url.queryParameter("maxBitRate"))
    }

    @Test
    fun `cover art is cached although every request uses a new salt`() = runTest {
        val image = byteArrayOf(1, 2, 3)
        mockServer.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("Cache-Control", "no-cache")
                .body(Buffer().write(image))
                .build(),
        )

        assertArrayEquals(image, api.coverArt(server, "al-1", 256))
        assertArrayEquals(image, api.coverArt(server, "al-1", 256))

        assertEquals(1, mockServer.requestCount)
        // Weder Cache-Schlüssel noch gespeicherte Anfrage enthalten Zugangsdaten.
        val cachedUrls = client.cache!!.urls().asSequence().toList()
        assertEquals(1, cachedUrls.size)
        assertTrue(cachedUrls.none { "t=" in it || "u=" in it })
    }

    @Test
    fun `wrong credentials are reported as auth error`() = runTest {
        mockServer.enqueue(json("error_wrong_credentials"))

        try {
            api.ping(server)
            fail("SubsonicException erwartet")
        } catch (e: SubsonicException) {
            assertEquals(SubsonicException.WRONG_CREDENTIALS, e.code)
            assertTrue(e.isAuthError)
        }
    }

    @Test(expected = NotSubsonicException::class)
    fun `html pages are not mistaken for subsonic`() = runTest {
        mockServer.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>Login</html>").build())
        api.ping(server)
    }

    @Test
    fun `navidrome song list is parsed with replay gain and multiple artists`() = runTest {
        mockServer.enqueue(json("search3_songs"))

        val songs = api.searchAllSongs(server, 0, 500)

        assertEquals(7, songs.size)
        val mondlicht = songs.single { it.title == "Mondlicht" }
        assertEquals(-6.5f, mondlicht.replayGain?.trackGain)
        assertEquals(0.98f, mondlicht.replayGain?.albumPeak)
        assertEquals("00000001-1111-4111-8111-111111111111", mondlicht.musicBrainzId)
        assertEquals(40, mondlicht.duration)
        assertEquals(listOf("Nachtfalter"), mondlicht.artists.map { it.name })
        val mp3 = songs.first { it.album == "Blaues Album" }
        assertNull(mp3.replayGain?.trackGain)
    }

    @Test
    fun `album list carries release mbid`() = runTest {
        mockServer.enqueue(json("album_list_newest"))

        val albums = api.albumList(server, "newest", 0, 50)

        assertEquals("22222222-2222-4222-8222-222222222222", albums.single { it.name == "Mondlicht" }.musicBrainzId)
    }

    @Test
    fun `starred songs carry the star time`() = runTest {
        mockServer.enqueue(json("starred2_song"))
        mockServer.enqueue(json("starred2"))

        val starred = api.starred(server).song.single()
        assertEquals("Dritter Song", starred.title)
        assertEquals("2026-10-27T20:27:35.10825678+01:00", starred.starred)
        assertEquals(emptyList<Any>(), api.starred(server).song)
    }

    @Test
    fun `starred albums and artists carry id and star time`() = runTest {
        mockServer.enqueue(json("starred2_collections"))

        val starred = api.starred(server)

        assertEquals("3ELfiCU2mKdlowo65K2oBs", starred.album.single().id)
        assertEquals("2026-11-13T09:13:32.319225102+01:00", starred.album.single().starred)
        assertEquals("65p93SVfjW3DDeVC0U2vz3", starred.artist.single().id)
        assertEquals("2026-11-13T09:13:32.320134196+01:00", starred.artist.single().starred)
    }

    @Test
    fun `structured lyrics keep the synced flag`() {
        val response = SubsonicApi.json.decodeFromString<de.kleinabersmart.earloft.core.network.subsonic.dto.SubsonicEnvelope>(
            fixture("lyrics_by_song_id"),
        ).response
        val lyrics = response.lyricsList!!.structuredLyrics.single()
        assertTrue(lyrics.synced)
        assertEquals(5, lyrics.line.size)
        assertEquals(5000L, lyrics.line[1].start)
    }

    @Test
    fun `star and scrobble send the song id`() = runTest {
        mockServer.enqueue(json("ping"))
        mockServer.enqueue(json("ping"))

        api.star(server, "s1")
        api.scrobble(server, "s1", playedAt = 1_793_000_000_000)

        val star = mockServer.takeRequest().url
        assertEquals("/navidrome/rest/star", star.encodedPath)
        assertEquals("s1", star.queryParameter("id"))
        val scrobble = mockServer.takeRequest().url
        assertEquals("/navidrome/rest/scrobble", scrobble.encodedPath)
        assertEquals("1793000000000", scrobble.queryParameter("time"))
        assertEquals("true", scrobble.queryParameter("submission"))
    }

    @Test
    fun `album and artist stars use their own parameter`() = runTest {
        mockServer.enqueue(json("ping"))
        mockServer.enqueue(json("ping"))

        api.star(server, "al1", FavoriteKind.ALBUM)
        api.unstar(server, "ar1", FavoriteKind.ARTIST)

        val star = mockServer.takeRequest().url
        assertEquals("al1", star.queryParameter("albumId"))
        assertEquals(null, star.queryParameter("id"))
        val unstar = mockServer.takeRequest().url
        assertEquals("/navidrome/rest/unstar", unstar.encodedPath)
        assertEquals("ar1", unstar.queryParameter("artistId"))
    }

    @Test
    fun `playlists are parsed from real responses`() = runTest {
        mockServer.enqueue(json("playlists"))
        mockServer.enqueue(json("playlist"))

        val summary = api.playlists(server).single()
        val playlist = api.playlist(server, summary.id)

        assertEquals("Fixture", summary.name)
        assertEquals("kal", summary.owner)
        assertTrue(summary.changed != null && !summary.readonly)
        assertEquals(listOf("Leise", "Morgengrau"), playlist.entry.map { it.title })
    }

    @Test
    fun `playlist update is posted as a form when the server supports formPost`() = runTest {
        mockServer.enqueue(json("ping"))

        api.updatePlaylist(server.copy(supportsFormPost = true), "pl1", "Mix", listOf(1, 0), listOf("a", "b"))

        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("kal", request.url.queryParameter("u"))
        assertNull(request.url.queryParameter("songIdToAdd"))
        assertEquals(
            "playlistId=pl1&name=Mix&songIndexToRemove=1&songIndexToRemove=0&songIdToAdd=a&songIdToAdd=b",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `playlist update without formPost uses query parameters`() = runTest {
        mockServer.enqueue(json("ping"))

        api.updatePlaylist(server, "pl1", null, emptyList(), listOf("a", "b"))

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(listOf("a", "b"), request.url.queryParameterValues("songIdToAdd"))
        assertNull(request.url.queryParameter("name"))
    }

    private fun json(name: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(fixture(name)).build()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("subsonic/$name.json")!!.readText()
}
