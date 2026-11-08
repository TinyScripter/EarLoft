package de.kleinabersmart.tinyamp.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.datasource.RawArtist
import de.kleinabersmart.tinyamp.core.datasource.RawTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewSongsCounterTest {

    private lateinit var db: TinyAmpDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var counter: NewSongsCounter
    private val nd = FakeServer("nd", listOf(track("a", "Erstes")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer()
        counter = NewSongsCounter(db.libraryDao(), db.sourceDao())
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
        nd.tracks = listOf(track("a", "Erstes"), track("b", "Zweites"), track("c", "Drittes"))

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(2, counter.newSongs(baseline))
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
        nd.tracks = listOf(track("a", "Erstes (Remaster)", lastModified = 2))

        val baseline = counter.begin(listOf("nd"))
        syncer.syncAdding(db, nd)

        assertEquals(0, counter.newSongs(baseline))
    }

    @Test
    fun `one source never synced makes the whole refresh a first import`() = runTest {
        syncer.syncAdding(db, nd)
        nd.tracks = listOf(track("a", "Erstes"), track("b", "Zweites"))

        val baseline = counter.begin(listOf("nd", "other"))
        syncer.syncAdding(db, nd)

        assertEquals(0, counter.newSongs(baseline))
    }

    private fun track(remoteId: String, title: String, lastModified: Long = 1) = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = "Sigur Rós",
        artists = listOf(RawArtist("Sigur Rós")),
        albumTitle = "Takk...",
        albumArtistDisplay = "Sigur Rós",
        albumArtists = listOf(RawArtist("Sigur Rós")),
        trackNumber = 3,
        durationMs = 270_000,
        year = 2005,
        lastModified = lastModified,
    )
}
