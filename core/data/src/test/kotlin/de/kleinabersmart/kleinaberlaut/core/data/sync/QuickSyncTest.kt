package de.kleinabersmart.kleinaberlaut.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.datasource.RawArtist
import de.kleinabersmart.kleinaberlaut.core.datasource.RawTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuickSyncTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var favorites: FavoritesRepository
    private val nd = FakeServer("nd", listOf(track("a", "Erstes")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer()
        favorites = FavoritesRepository(db, db.libraryDao(), db.syncDao(), NoScheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `new albums are added without a full scan`() = runTest {
        syncer.sync(nd)
        nd.newTracks = listOf(track("b", "Zweites"))

        assertTrue(syncer.quickSync(nd))

        assertEquals(listOf("Erstes", "Zweites"), titles())
        val lastFullSyncAt = db.sourceDao().get("nd")!!.lastFullSyncAt
        assertTrue(nd.lastSince < lastFullSyncAt)
    }

    @Test
    fun `quick sync never deletes and keeps the generation`() = runTest {
        syncer.sync(nd)
        val before = db.sourceDao().get("nd")!!
        nd.tracks = emptyList()

        assertTrue(syncer.quickSync(nd))

        assertEquals(listOf("Erstes"), titles())
        assertEquals(before.syncGeneration, db.sourceDao().get("nd")!!.syncGeneration)
        // Das nächste Vollabgleich räumt wie gewohnt auf.
        syncer.sync(nd)
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `quick sync needs a full sync first`() = runTest {
        nd.newTracks = listOf(track("b", "Zweites"))

        assertFalse(syncer.quickSync(nd))

        assertEquals(-1L, nd.lastSince)
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `sources without quick sync are skipped`() = runTest {
        assertFalse(syncer.quickSync(FakeLocalSource(emptyList())))
    }

    @Test
    fun `favorites changed by another client are picked up`() = runTest {
        syncer.sync(nd)

        nd.starred = mapOf("a" to 5_000L)
        assertTrue(syncer.quickSync(nd))
        assertTrue(song().isFavorite)
        assertEquals(5_000L, db.syncDao().getSongSource("nd:a")!!.starredAt)
        assertEquals(0, db.syncDao().countActions())

        nd.starred = emptyMap()
        assertTrue(syncer.quickSync(nd))
        assertFalse(song().isFavorite)
    }

    @Test
    fun `unsent local favorite change wins over the server`() = runTest {
        nd.tracks = listOf(track("a", "Erstes", starredAt = 1_000))
        syncer.sync(nd)
        favorites.setFavorite(song().songId, false)

        nd.starred = mapOf("a" to 2_000L)
        syncer.quickSync(nd)

        assertFalse(song().isFavorite)
        assertEquals(listOf("false"), db.syncDao().getActions().map { it.payloadJson })
    }

    @Test
    fun `server playlists are synced as well`() = runTest {
        syncer.sync(nd)
        nd.putPlaylist("Mix", listOf("a"))

        assertTrue(syncer.quickSync(nd))

        assertEquals(1, db.playlistDao().getBoundTo("nd").size)
    }

    @Test
    fun `an unreachable server changes nothing and is not reported as a failure`() = runTest {
        syncer.sync(nd)
        nd.newTracks = listOf(track("b", "Zweites"))
        nd.failure = java.io.IOException("offline")

        assertFalse(syncer.quickSync(nd))

        assertEquals(listOf("Erstes"), titles())
        assertTrue(syncer.failures.value.isEmpty())
    }

    private suspend fun titles() = db.libraryDao().observeSongRows().first().map { it.title }.sorted()

    private suspend fun song() = db.libraryDao().observeSongRows().first().single()

    private fun track(remoteId: String, title: String, starredAt: Long? = null) = RawTrack(
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
        lastModified = 1,
        starredAt = starredAt,
    )
}
