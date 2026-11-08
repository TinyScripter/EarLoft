package de.kleinabersmart.earloft.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.LyricsRepository
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.LyricsEntity
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.LyricsKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class LyricsRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var lyrics: LyricsRepository
    private val nd = FakeServer("nd", listOf(track("a"), track("b")))
    private val synced = Lyrics.Synced(listOf(LyricLine(0, "Eins"), LyricLine(5_000, "Zweiä")))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.librarySyncer().syncAdding(db, nd)
        lyrics = LyricsRepository(db.lyricsDao(), db.mergeDao(), FixedSources(nd))
    }

    @After
    fun tearDown() {
        db.close()
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

        // Eintrag altern lassen: nach Ablauf wird neu gefragt und der inzwischen ergänzte Text gefunden.
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
    }

    private suspend fun songId(remoteId: String) = db.syncDao().getSongSource("nd:$remoteId")!!.songId

    private fun track(remoteId: String) = RawTrack(
        remoteId = remoteId,
        title = "Titel $remoteId",
        artistDisplay = "Band",
        artists = listOf(RawArtist("Band")),
        albumTitle = "Album",
        albumArtistDisplay = "Band",
        albumArtists = listOf(RawArtist("Band")),
        durationMs = 100_000,
        lastModified = 1,
    )
}
