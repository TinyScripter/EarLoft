package de.kleinabersmart.earloft.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewSongsCounterTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var counter: NewSongsCounter
    private val nd = FakeServer("nd", listOf(track("a", "First")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val adopted = AdoptedSongs()
        syncer = db.librarySyncer(adopted = adopted)
        counter = NewSongsCounter(db.libraryDao(), db.sourceDao(), adopted)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the first import of a source counts nothing`() = runTest {
        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(0, counter.newSongs(baseline))
    }

    @Test
    fun `songs added after the first import are counted`() = runTest {
        syncer.syncAdding(db, nd)
        nd.tracks = listOf(track("a", "First"), track("b", "Second"), track("c", "Third"))

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(2, counter.newSongs(baseline))
    }

    @Test
    fun `new songs are grouped by album with the most new songs first`() = runTest {
        syncer.syncAdding(db, nd)
        nd.tracks = listOf(
            track("a", "First"),
            track("b", "Second"),
            track("c", "Glósóli", album = "Takk... (Live)"),
            track("d", "Hoppípolla", album = "Takk... (Live)"),
            track("e", "Sæglópur", album = "Takk... (Live)"),
        )

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        val albums = counter.newAlbums(baseline)
        assertEquals(listOf("Takk... (Live)" to 3, "Takk..." to 1), albums.map { it.title to it.songs })
        assertEquals("Sigur Rós", albums.first().artist)
    }

    @Test
    fun `the first import lists no new albums`() = runTest {
        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(emptyList<Any>(), counter.newAlbums(baseline))
    }

    @Test
    fun `a refresh without changes counts nothing and removed songs are not subtracted`() = runTest {
        syncer.syncAdding(db, nd)
        val unchanged = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)
        assertEquals(0, counter.newSongs(unchanged))

        nd.tracks = emptyList()
        val removed = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)
        assertEquals(0, counter.newSongs(removed))
    }

    @Test
    fun `retagged files keep their song and are not new`() = runTest {
        syncer.syncAdding(db, nd)
        nd.tracks = listOf(track("a", "First (Remaster)", lastModified = 2))

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(0, counter.newSongs(baseline))
    }

    @Test
    fun `a track the server delivers under a new id is no new music`() = runTest {
        syncer.syncAdding(db, nd)
        // The server moved the folder: the same track under a new ID, plus one really new track on another album.
        nd.tracks = listOf(track("a2", "First"), track("b", "Second", album = "Hvarf"))

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(1, counter.newSongs(baseline))
        assertEquals(listOf("Hvarf" to 1), counter.newAlbums(baseline).map { it.title to it.songs })
    }

    @Test
    fun `one source never synced makes the whole refresh a first import`() = runTest {
        syncer.syncAdding(db, nd)
        nd.tracks = listOf(track("a", "First"), track("b", "Second"))

        val baseline = counter.begin(listOf("nd", "other"))
        syncer.syncAdding(db, nd)

        assertEquals(0, counter.newSongs(baseline))
    }

    private fun track(remoteId: String, title: String, lastModified: Long = 1, album: String = "Takk...") = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = "Sigur Rós",
        artists = listOf(RawArtist("Sigur Rós")),
        albumTitle = album,
        albumArtistDisplay = "Sigur Rós",
        albumArtists = listOf(RawArtist("Sigur Rós")),
        trackNumber = 3,
        durationMs = 270_000,
        year = 2005,
        lastModified = lastModified,
    )
}
