package de.kleinabersmart.kleinaberlaut.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import de.kleinabersmart.kleinaberlaut.core.datasource.RawArtist
import de.kleinabersmart.kleinaberlaut.core.datasource.RawTrack
import de.kleinabersmart.kleinaberlaut.core.model.SyncActionType
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
class FavoritesSyncTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var favorites: FavoritesRepository
    private val scheduler = CountingScheduler()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer(scheduler)
        favorites = FavoritesRepository(db, db.libraryDao(), db.syncDao(), scheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `server star becomes a favorite and unstarring removes it`() = runTest {
        syncer.sync(source("nd", track("a", starredAt = 1_000)))
        assertTrue(song().isFavorite)

        syncer.sync(source("nd", track("a", starredAt = null)))
        assertFalse(song().isFavorite)
    }

    @Test
    fun `server unstar is applied after a sent local star`() = runTest {
        val nd = source("nd", track("a"))
        syncer.sync(nd)
        favorites.setFavorite(song().songId, true)
        assertTrue(db.syncQueueProcessor(nd).process())
        assertEquals(0, db.syncDao().countActions())

        // Auf dem Server wieder entsternt: Er liefert denselben Track wie beim ersten Abgleich.
        syncer.sync(nd)

        assertFalse(song().isFavorite)
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `unsent local change wins over the server`() = runTest {
        syncer.sync(source("nd", track("a", starredAt = 1_000)))
        favorites.setFavorite(song().songId, false)

        syncer.sync(source("nd", track("a", starredAt = 2_000)))

        assertFalse(song().isFavorite)
        assertEquals(listOf("false"), db.syncDao().getActions().map { it.payloadJson })
    }

    @Test
    fun `starring and unstarring before sending sends nothing`() = runTest {
        syncer.sync(source("nd", track("a")))
        val songId = song().songId

        favorites.setFavorite(songId, true)
        assertEquals(1, db.syncDao().countActions())
        favorites.setFavorite(songId, false)

        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `favorite goes to every server of the song`() = runTest {
        syncer.sync(source("nd", track("a")))
        syncer.sync(source("office", track("x")))

        favorites.setFavorite(song().songId, true)

        val actions = db.syncDao().getActions()
        assertEquals(setOf("nd:a", "office:x"), actions.map { it.targetKey }.toSet())
        assertTrue(actions.all { it.actionType == SyncActionType.SET_FAVORITE && it.payloadJson == "true" })
    }

    @Test
    fun `local favorite is kept and sent when a server adds the song`() = runTest {
        syncer.sync(source("local", track("1")))
        favorites.setFavorite(song().songId, true)
        assertEquals(0, db.syncDao().countActions())
        val scheduledBefore = scheduler.calls

        syncer.sync(source("nd", track("a")))

        assertTrue(song().isFavorite)
        assertEquals(listOf("nd:a"), db.syncDao().getActions().map { it.targetKey })
        assertTrue(scheduler.calls > scheduledBefore)
    }

    @Test
    fun `star on one server is passed on to the others`() = runTest {
        syncer.sync(source("nd", track("a")))
        syncer.sync(source("office", track("x")))

        syncer.sync(source("nd", track("a", starredAt = 5_000)))

        assertTrue(song().isFavorite)
        assertEquals(listOf("office:x"), db.syncDao().getActions().map { it.targetKey })
    }

    private suspend fun song() = db.libraryDao().observeSongRows().first().single()

    private fun track(remoteId: String, starredAt: Long? = null) = RawTrack(
        remoteId = remoteId,
        title = "Hoppípolla",
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

    private fun source(sourceId: String, vararg tracks: RawTrack): MusicDataSource =
        if (sourceId == "local") FakeLocalSource(tracks.toList()) else FakeServer(sourceId, tracks.toList())

    private class CountingScheduler : SyncScheduler {
        var calls = 0
        override suspend fun enqueue() {
            calls++
        }
    }

}
