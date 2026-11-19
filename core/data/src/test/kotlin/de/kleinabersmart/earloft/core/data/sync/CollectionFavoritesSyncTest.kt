package de.kleinabersmart.earloft.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.SyncActionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Album and artist favorites between the app and the servers (concept 6.4). */
@RunWith(RobolectricTestRunner::class)
class CollectionFavoritesSyncTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var favorites: FavoritesRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
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
    fun `server stars of albums and artists become favorites`() = runTest {
        val nd = FakeServer("nd", listOf(track("a"), track("b", trackNumber = 2)))
        nd.starredAlbums = mapOf("al1" to 5_000L)
        nd.starredArtists = mapOf("ar1" to 0L)

        syncer.syncAdding(db, nd)

        assertEquals(listOf("Takk..."), favorites.favoriteAlbums().first().map { it.title })
        assertEquals(5_000L, db.libraryDao().observeAlbums().first().single().favoriteChangedAt)
        assertEquals(listOf("Sigur Rós"), favorites.favoriteArtists().first().map { it.name })
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `local album favorite is sent to every server of the album`() = runTest {
        val nd = FakeServer("nd", listOf(track("a")))
        val office = FakeServer("office", listOf(track("x", albumRemoteId = "o-al", artistRemoteId = "o-ar")))
        syncer.syncAdding(db, nd)
        syncer.syncAdding(db, office)

        favorites.setCollectionFavorite(FavoriteKind.ALBUM, albumId(), true)

        val actions = db.syncDao().getActions()
        assertEquals(setOf("nd" to "al1", "office" to "o-al"), actions.map { it.sourceId to it.targetKey }.toSet())
        assertTrue(actions.all { it.actionType == SyncActionType.SET_ALBUM_FAVORITE && it.payloadJson == "true" })

        assertTrue(db.syncQueueProcessor(nd, office).process())
        assertEquals(listOf("favorite album al1 true"), nd.calls)
        assertEquals(listOf("favorite album o-al true"), office.calls)
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `server unstar in quick sync removes the favorite after it was sent`() = runTest {
        val nd = FakeServer("nd", listOf(track("a")))
        syncer.syncAdding(db, nd)
        favorites.setCollectionFavorite(FavoriteKind.ARTIST, artistId(), true)
        assertTrue(db.syncQueueProcessor(nd).process())
        assertEquals(listOf("favorite artist ar1 true"), nd.calls)

        // The server (again) doesn't list the artist: unstarred on another device.
        assertTrue(syncer.quickSync(nd))

        assertFalse(favorites.isArtistFavorite(artistId()).first())
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `unsent local change wins over the server`() = runTest {
        val nd = FakeServer("nd", listOf(track("a")))
        nd.starredAlbums = mapOf("al1" to 1_000L)
        syncer.syncAdding(db, nd)
        favorites.setCollectionFavorite(FavoriteKind.ALBUM, albumId(), false)

        nd.starredAlbums = mapOf("al1" to 2_000L)
        syncer.syncAdding(db, nd)

        assertFalse(favorites.isAlbumFavorite(albumId()).first())
        assertEquals(listOf("false"), db.syncDao().getActions().map { it.payloadJson })
    }

    @Test
    fun `star on one server is passed on to the others`() = runTest {
        val nd = FakeServer("nd", listOf(track("a")))
        val office = FakeServer("office", listOf(track("x", albumRemoteId = "o-al", artistRemoteId = "o-ar")))
        syncer.syncAdding(db, nd)
        syncer.syncAdding(db, office)

        nd.starredAlbums = mapOf("al1" to 1_000L)
        assertTrue(syncer.quickSync(nd))

        assertTrue(favorites.isAlbumFavorite(albumId()).first())
        assertEquals(listOf("office" to "o-al"), db.syncDao().getActions().map { it.sourceId to it.targetKey })
    }

    @Test
    fun `local favorite is sent when a server adds the album`() = runTest {
        syncer.syncAdding(db, FakeLocalSource(listOf(track("1", albumRemoteId = null, artistRemoteId = null))))
        favorites.setCollectionFavorite(FavoriteKind.ALBUM, albumId(), true)
        assertEquals(0, db.syncDao().countActions())

        syncer.syncAdding(db, FakeServer("nd", listOf(track("a"))))

        assertTrue(favorites.isAlbumFavorite(albumId()).first())
        assertEquals(listOf("al1"), db.syncDao().getActions().map { it.targetKey })
    }

    @Test
    fun `album gone from the server drops its pending star`() = runTest {
        val nd = FakeServer("nd", listOf(track("a")))
        syncer.syncAdding(db, nd)
        favorites.setCollectionFavorite(FavoriteKind.ALBUM, albumId(), true)

        // The server now lists the album under a new ID; the old one no longer exists.
        nd.tracks = listOf(track("a", albumRemoteId = "al2"))
        syncer.syncAdding(db, nd)
        assertNull(db.mergeDao().getAlbumSource("nd", "al1"))

        assertTrue(db.syncQueueProcessor(nd).process())
        // The new ID is a new source of the favorited album and gets the star.
        assertEquals(listOf("favorite album al2 true"), nd.calls)
    }

    @Test
    fun `starring and unstarring before sending sends nothing`() = runTest {
        syncer.syncAdding(db, FakeServer("nd", listOf(track("a"))))

        favorites.setCollectionFavorite(FavoriteKind.ALBUM, albumId(), true)
        favorites.toggleCollectionFavorite(FavoriteKind.ALBUM, albumId())

        assertFalse(favorites.isAlbumFavorite(albumId()).first())
        assertEquals(0, db.syncDao().countActions())
    }

    private suspend fun albumId() = db.libraryDao().observeAlbumRows().first().single().albumId

    private suspend fun artistId() = db.libraryDao().observeArtistRows().first().single().artistId

    private fun track(
        remoteId: String,
        trackNumber: Int = 1,
        albumRemoteId: String? = "al1",
        artistRemoteId: String? = "ar1",
    ) = RawTrack(
        remoteId = remoteId,
        title = "Song $trackNumber",
        artistDisplay = "Sigur Rós",
        artists = listOf(RawArtist("Sigur Rós", remoteId = artistRemoteId)),
        albumTitle = "Takk...",
        albumArtistDisplay = "Sigur Rós",
        albumArtists = listOf(RawArtist("Sigur Rós", remoteId = artistRemoteId)),
        albumRemoteId = albumRemoteId,
        trackNumber = trackNumber,
        durationMs = 200_000,
        lastModified = 1,
    )
}
