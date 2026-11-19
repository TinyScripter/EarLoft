package de.kleinabersmart.earloft.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.LrclibClient
import de.kleinabersmart.earloft.core.data.repository.LyricsRepository
import de.kleinabersmart.earloft.core.data.repository.LyricsSettings
import de.kleinabersmart.earloft.core.data.repository.SongLyrics
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.LyricsEntity
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.LyricsKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LyricsRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var lyrics: LyricsRepository
    private lateinit var settings: LyricsSettings
    private val nd = FakeServer("nd", listOf(track("a"), track("b", artist = "Band feat. Gast"), track("c")))
    private val synced = Lyrics.Synced(listOf(LyricLine(0, "Eins"), LyricLine(5_000, "Zweiä")))

    // Fake LRCLIB: responses per path, all requests recorded for inspection.
    private val lrclib = MockWebServer()
    private val lrclibResponses = mutableMapOf<String, MockResponse>()
    private val lrclibRequests = CopyOnWriteArrayList<RecordedRequest>()

    /** Answered in order before [lrclibResponses]. */
    private val lrclibFirst = java.util.concurrent.ConcurrentLinkedQueue<MockResponse>()

    /** Runs while LRCLIB receives a request (the query is then in flight). */
    @Volatile
    private var onLrclibRequest: (() -> Unit)? = null

    @Before
    fun setUp() = runTest {
        lrclib.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lrclibRequests += request
                onLrclibRequest?.invoke()
                lrclibFirst.poll()?.let { return it }
                return lrclibResponses[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        lrclib.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.librarySyncer().syncAdding(db, nd)
        settings = LyricsSettings(context)
        val client = LrclibClient(OkHttpClient(), lrclib.url("/").toString().trimEnd('/'))
        lyrics = LyricsRepository(db.lyricsDao(), db.mergeDao(), FixedSources(nd), settings, client)
        lyrics.setOnlineLyrics(false)
    }

    @After
    fun tearDown() {
        db.close()
        lrclib.close()
    }

    @Test
    fun `found lyrics are cached and not fetched twice`() = runTest {
        nd.lyrics["a"] = synced

        assertEquals(synced, lyrics.lyrics(songId("a")))
        assertEquals(synced, lyrics.lyrics(songId("a")))

        assertEquals(listOf("lyrics a"), nd.calls)
    }

    @Test
    fun `plain lyrics survive the round trip`() = runTest {
        nd.lyrics["a"] = Lyrics.Plain("Zeile 1\nZeile 2")

        lyrics.lyrics(songId("a"))

        assertEquals(Lyrics.Plain("Zeile 1\nZeile 2"), lyrics.lyrics(songId("a")))
        assertEquals(1, nd.calls.size)
    }

    @Test
    fun `missing lyrics are remembered for a while and then asked again`() = runTest {
        val id = songId("b")

        assertEquals(Lyrics.None, lyrics.lyrics(id))
        assertEquals(Lyrics.None, lyrics.lyrics(id))
        assertEquals(1, nd.calls.size)

        // Let the entry age: after expiry it is queried again and the lyrics added in the meantime are found.
        db.lyricsDao().upsert(LyricsEntity(id, LyricsKind.NONE, "", "", fetchedAt = 0))
        nd.lyrics["b"] = synced
        assertEquals(synced, lyrics.lyrics(id))
        assertEquals(2, nd.calls.size)
    }

    @Test
    fun `unreachable server is not cached as missing`() = runTest {
        val id = songId("a")
        nd.failure = IOException("offline")

        assertEquals(Lyrics.Unavailable, lyrics.lyrics(id))

        nd.failure = null
        nd.lyrics["a"] = synced
        assertEquals(synced, lyrics.lyrics(id))
    }

    @Test
    fun `song without server source has no lyrics and asks nobody`() = runTest {
        val id = songId("a")
        db.openHelper.writableDatabase.execSQL("DELETE FROM song_sources")

        assertEquals(Lyrics.None, lyrics.lyrics(id))
        assertEquals(emptyList<String>(), nd.calls)
        assertEquals(0, lrclibRequests.size)
    }

    @Test
    fun `switched off, LRCLIB is not asked`() = runTest {
        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online")

        assertEquals(Lyrics.None, lyrics.lyrics(songId("b")))
        assertEquals(0, lrclibRequests.size)
    }

    @Test
    fun `LRCLIB fills in what the servers lack, asked with title, artist and duration`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online\n[00:04.50] Zwei")
        val id = songId("b")

        val expected = Lyrics.Synced(listOf(LyricLine(1_000, "Online"), LyricLine(4_500, "Zwei")))
        assertEquals(SongLyrics(expected, fromLrclib = true), lyrics.songLyrics(id))
        // From the cache, too, with source.
        assertEquals(SongLyrics(expected, fromLrclib = true), lyrics.songLyrics(id))

        val request = lrclibRequests.single().url
        assertEquals("Titel b", request.queryParameter("track_name"))
        assertEquals("Band feat. Gast", request.queryParameter("artist_name"))
        assertEquals("100", request.queryParameter("duration"))
        assertEquals("lrclib", db.lyricsDao().get(id)!!.origin)
    }

    @Test
    fun `without a hit for the full artist the first one is asked`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/get"] = MockResponse.Builder().code(404).build()

        assertEquals(Lyrics.None, lyrics.lyrics(songId("b")))

        val asked = lrclibRequests.filter { it.url.encodedPath == "/api/get" }.map { it.url.queryParameter("artist_name") }
        assertEquals(listOf("Band feat. Gast", "Band"), asked)
    }

    @Test
    fun `synced lyrics from LRCLIB beat plain ones from the server, plain ones do not`() = runTest {
        lyrics.setOnlineLyrics(true)
        nd.lyrics["a"] = Lyrics.Plain("Vom Server")
        nd.lyrics["b"] = Lyrics.Plain("Auch vom Server")
        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online")

        assertEquals(Lyrics.Synced(listOf(LyricLine(1_000, "Online"))), lyrics.lyrics(songId("a")))

        lrclibResponses["/api/get"] = entry(plain = "Online statisch")
        assertEquals(SongLyrics(Lyrics.Plain("Auch vom Server")), lyrics.songLyrics(songId("b")))
        assertEquals("nd", db.lyricsDao().get(songId("b"))!!.origin)
    }

    @Test
    fun `search results only count with the same title and artist and only as plain text`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/search"] = json(
            """
            [
              {"trackName":"Anderer Song","artistName":"Band","instrumental":false,"plainLyrics":"Falsch","syncedLyrics":null},
              {"trackName":"Titel A","artistName":"band","instrumental":false,"plainLyrics":"Richtig\nZeile 2","syncedLyrics":"[00:01.00] Live"}
            ]
            """,
        )

        assertEquals(Lyrics.Plain("Richtig\nZeile 2"), lyrics.lyrics(songId("a")))
    }

    @Test
    fun `instrumental entries have no lyrics`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/get"] = json("""{"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""")

        assertEquals(Lyrics.None, lyrics.lyrics(songId("a")))
    }

    @Test
    fun `unreachable LRCLIB is not cached as missing`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/get"] = MockResponse.Builder().code(503).build()
        val id = songId("a")

        assertEquals(Lyrics.Unavailable, lyrics.lyrics(id))
        assertNull(db.lyricsDao().get(id))

        lrclibResponses["/api/get"] = json("not json")
        assertEquals(Lyrics.Unavailable, lyrics.lyrics(id))
    }

    @Test
    fun `a plain server text is not stored while LRCLIB was unreachable`() = runTest {
        lyrics.setOnlineLyrics(true)
        nd.lyrics["a"] = Lyrics.Plain("Vom Server")
        lrclibResponses["/api/get"] = MockResponse.Builder().code(503).build()
        val id = songId("a")

        assertEquals(Lyrics.Plain("Vom Server"), lyrics.lyrics(id))
        assertNull(db.lyricsDao().get(id))

        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online")
        assertEquals(SongLyrics(Lyrics.Synced(listOf(LyricLine(1_000, "Online"))), fromLrclib = true), lyrics.songLyrics(id))
    }

    @Test
    fun `a LRCLIB answer arriving after switching off is neither shown nor stored`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online")
        onLrclibRequest = { runBlocking { lyrics.setOnlineLyrics(false) } }
        val id = songId("a")

        assertEquals(SongLyrics(Lyrics.None), lyrics.songLyrics(id))

        assertEquals("", db.lyricsDao().get(id)!!.origin)
        assertEquals(LyricsKind.NONE, db.lyricsDao().get(id)!!.kind)
    }

    @Test
    fun `a short rate limit is waited out once, a long one counts as unreachable`() = runTest {
        lyrics.setOnlineLyrics(true)
        lrclibFirst += MockResponse.Builder().code(429).addHeader("Retry-After", "0").build()
        lrclibResponses["/api/get"] = entry(plain = "Online")

        assertEquals(Lyrics.Plain("Online"), lyrics.lyrics(songId("a")))
        assertEquals(2, lrclibRequests.size)

        lrclibFirst += MockResponse.Builder().code(429).addHeader("Retry-After", "60").build()
        assertEquals(Lyrics.Unavailable, lyrics.lyrics(songId("c")))
        assertEquals(3, lrclibRequests.size)
    }

    @Test
    fun `local songs are looked up online too`() = runTest {
        lyrics.setOnlineLyrics(true)
        val id = songId("a")
        db.openHelper.writableDatabase.execSQL("DELETE FROM song_sources")
        lrclibResponses["/api/get"] = entry(plain = "Online")

        assertEquals(Lyrics.Plain("Online"), lyrics.lyrics(id))
        assertEquals(emptyList<String>(), nd.calls)
    }

    @Test
    fun `switching on forgets missing and plain server lyrics, switching off forgets those from LRCLIB`() = runTest {
        val a = songId("a")
        val b = songId("b")
        val c = songId("c")
        nd.lyrics["a"] = Lyrics.Plain("Vom Server")
        lyrics.lyrics(a)
        lyrics.lyrics(b)
        db.lyricsDao().upsert(LyricsEntity(c, LyricsKind.PLAIN, "Datei", "embedded", fetchedAt = 1))

        lyrics.setOnlineLyrics(true)
        assertNull(db.lyricsDao().get(a))
        assertNull(db.lyricsDao().get(b))
        assertEquals("embedded", db.lyricsDao().get(c)!!.origin)

        lrclibResponses["/api/get"] = entry(synced = "[00:01.00] Online")
        lyrics.lyrics(b)
        lyrics.setOnlineLyrics(false)
        assertNull(db.lyricsDao().get(b))
        assertEquals(Lyrics.None, lyrics.lyrics(b))
    }

    private fun entry(synced: String? = null, plain: String? = null): MockResponse {
        val body = org.json.JSONObject()
            .put("trackName", "Titel")
            .put("artistName", "Band")
            .put("instrumental", false)
            .put("syncedLyrics", synced ?: org.json.JSONObject.NULL)
            .put("plainLyrics", plain ?: org.json.JSONObject.NULL)
        return json(body.toString())
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body.trimIndent()).build()

    private suspend fun songId(remoteId: String) = db.syncDao().getSongSource("nd:$remoteId")!!.songId

    private fun track(remoteId: String, artist: String = "Band") = RawTrack(
        remoteId = remoteId,
        title = "Titel $remoteId",
        artistDisplay = artist,
        artists = listOf(RawArtist("Band")),
        albumTitle = "Album",
        albumArtistDisplay = "Band",
        albumArtists = listOf(RawArtist("Band")),
        durationMs = 100_000,
        lastModified = 1,
    )
}
