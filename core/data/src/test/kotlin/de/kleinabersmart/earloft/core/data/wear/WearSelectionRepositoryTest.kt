package de.kleinabersmart.earloft.core.data.wear

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.data.sync.FakeLocalSource
import de.kleinabersmart.earloft.core.data.sync.FakeServer
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.sync.syncAdding
import de.kleinabersmart.earloft.core.data.sync.NoScheduler
import de.kleinabersmart.earloft.core.data.sync.librarySyncer
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WearSelectionRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var playlists: PlaylistRepository
    private lateinit var repository: WearSelectionRepository
    private val nd = FakeServer(
        "nd",
        listOf(track("c", "C", 3, sizeBytes = 3_000), track("a", "A", 1, sizeBytes = 1_000), track("b", "B", 2, sizeBytes = 2_000)),
    )

    /** "A" also exists locally (merged with the server song), "Sommerlied" only locally. */
    private val local = FakeLocalSource(
        listOf(track("1", "A", 1, sizeBytes = 9_000), track("2", "Sommerlied", 1, album = "Lokal", sizeBytes = 4_000)),
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer()
        playlists = PlaylistRepository(db, db.playlistDao(), db.syncDao(), NoScheduler)
        repository = WearSelectionRepository(db, db.wearDao(), db.playlistDao())
        syncer.syncAdding(db, nd)
        syncer.syncAdding(db, local)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `album resolves to its songs in track order with all server sources`() = runTest {
        repository.setSelected(WearSelectionKind.ALBUM, albumId("b"), selected = true)

        val content = repository.load()

        val selection = content.selections.single()
        assertEquals("album:${albumId("b")}", selection.key)
        assertEquals("Album", selection.title)
        assertEquals(listOf("A", "B", "C"), selection.songIds.map { title(content, it) })
        val b = content.songs.single { it.title == "B" }
        assertEquals("nd:b", b.key)
        assertEquals(listOf("nd:b"), b.serverSources.map { it.uid })
        assertEquals(2_000L, b.serverSources.single().sizeBytes)
        assertEquals(listOf("Band"), b.artists)
        assertNull(b.phoneFile)
    }

    @Test
    fun `songs in several selections appear once, playlists keep duplicates and order`() = runTest {
        val playlistId = playlists.create("Mix", serverSourceId = null, listOf(songId("b"), songId("a"), songId("b")))
        repository.setSelected(WearSelectionKind.ALBUM, albumId("a"), selected = true)
        repository.setSelected(WearSelectionKind.PLAYLIST, playlistId, selected = true)

        val content = repository.load()

        assertEquals(listOf("album:${albumId("a")}", "playlist:$playlistId"), content.selections.map { it.key })
        val mix = content.selections[1]
        assertEquals("Mix", mix.title)
        assertEquals(listOf("B", "A", "B"), mix.songIds.map { title(content, it) })
        assertEquals(3, content.songs.size)
        assertEquals(content.songs.map { it.songId }.toSet().size, content.songs.size)
    }

    @Test
    fun `local songs carry the phone file, local-only songs have no server source`() = runTest {
        val playlistId = playlists.create("Lokal", serverSourceId = null, listOf(songId("a"), localSongId("2")))
        repository.setSelected(WearSelectionKind.PLAYLIST, playlistId, selected = true)

        val songs = repository.load().songs

        val a = songs.single { it.title == "A" }
        assertEquals("local:1", a.key)
        assertEquals(listOf("nd:a"), a.serverSources.map { it.uid })
        assertEquals(WearPhoneFile("local:1", 9_000, "audio/mpeg"), a.phoneFile)
        val summer = songs.single { it.title == "Sommerlied" }
        assertTrue(summer.serverSources.isEmpty())
        assertEquals("local:2", summer.phoneFile?.uid)
    }

    @Test
    fun `favorites follow the library and keep the newest first`() = runTest {
        db.syncDao().setSongFavorite(songId("a"), true, changedAt = 1)
        db.syncDao().setSongFavorite(songId("c"), true, changedAt = 2)
        repository.setSelected(WearSelectionKind.FAVORITES, null, selected = true)

        val content = repository.load()

        val favorites = content.selections.single()
        assertEquals("favorites", favorites.key)
        assertEquals(listOf("C", "A"), favorites.songIds.map { title(content, it) })
    }

    @Test
    fun `favorite albums come along whole, after the favorite songs and without duplicates`() = runTest {
        db.syncDao().setSongFavorite(songId("b"), true, changedAt = 1)
        db.syncDao().setAlbumFavorite(albumId("a"), true, changedAt = 2)
        db.syncDao().setAlbumFavorite(db.mergeDao().getSong(localSongId("2"))!!.albumId, true, changedAt = 3)
        repository.setSelected(WearSelectionKind.FAVORITES, null, selected = true)

        val content = repository.load()

        assertEquals(listOf("B", "Sommerlied", "A", "C"), content.selections.single().songIds.map { title(content, it) })
    }

    @Test
    fun `content follows playlist changes and drops deleted playlists`() = runTest {
        val playlistId = playlists.create("Mix", serverSourceId = null, listOf(songId("a")))
        repository.setSelected(WearSelectionKind.PLAYLIST, playlistId, selected = true)
        val emissions = Channel<WearContent>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.content().collect { emissions.send(it) } }
        assertEquals(1, emissions.receive().songs.size)

        playlists.addSongs(playlistId, listOf(songId("c")))
        assertEquals(listOf("A", "C"), awaitContent(emissions) { it.songs.size == 2 }.songs.map { it.title })

        playlists.delete(playlistId)
        awaitContent(emissions) { it.selections.isEmpty() }
        assertTrue(db.wearDao().getSelections().isEmpty())
    }

    @Test
    fun `selecting again keeps the original position`() = runTest {
        repository.setSelected(WearSelectionKind.FAVORITES, null, selected = true)
        val addedAt = db.wearDao().getSelections().single().addedAt
        Thread.sleep(5)

        repository.setSelected(WearSelectionKind.FAVORITES, null, selected = true)

        assertEquals(addedAt, db.wearDao().getSelections().single().addedAt)
        repository.setSelected(WearSelectionKind.FAVORITES, null, selected = false)
        assertTrue(db.wearDao().getSelections().isEmpty())
    }

    private suspend fun awaitContent(emissions: Channel<WearContent>, predicate: (WearContent) -> Boolean): WearContent {
        while (true) {
            val content = emissions.receive()
            if (predicate(content)) return content
        }
    }

    private fun title(content: WearContent, songId: Long) = content.songs.single { it.songId == songId }.title

    private suspend fun songId(remoteId: String) = db.syncDao().getSongSource("nd:$remoteId")!!.songId

    private suspend fun localSongId(remoteId: String) = db.syncDao().getSongSource("local:$remoteId")!!.songId

    private suspend fun albumId(remoteId: String) = db.mergeDao().getSong(songId(remoteId))!!.albumId

    private fun track(remoteId: String, title: String, trackNumber: Int, album: String = "Album", sizeBytes: Long) = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = "Band",
        artists = listOf(RawArtist("Band")),
        albumTitle = album,
        albumArtistDisplay = "Band",
        albumArtists = listOf(RawArtist("Band")),
        trackNumber = trackNumber,
        durationMs = 100_000,
        mimeType = "audio/mpeg",
        sizeBytes = sizeBytes,
        lastModified = 1,
    )
}
