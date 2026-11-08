package de.kleinabersmart.earloft.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistSyncTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var playlists: PlaylistRepository
    private val notices = SyncNotices()
    private val nd = FakeServer("nd", listOf(track("a", "A", 1), track("b", "B", 2), track("c", "C", 3)))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer(notices = notices)
        playlists = PlaylistRepository(db, db.playlistDao(), db.syncDao(), NoScheduler)
        syncer.syncAdding(db, nd)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `server playlists are imported in order with duplicates`() = runTest {
        nd.putPlaylist("Mix", listOf("b", "a", "b", "unbekannt"))

        syncer.syncAdding(db, nd)

        val playlist = playlists.playlists().first().single()
        assertEquals("Mix", playlist.name)
        assertEquals("nd", playlist.serverName)
        assertEquals(3, playlist.songCount)
        assertEquals(listOf("B", "A", "B"), titles(playlist.id))
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `local change is sent when the server playlist is unchanged`() = runTest {
        nd.putPlaylist("Mix", listOf("a"))
        syncer.syncAdding(db, nd)
        val id = playlists.playlists().first().single().id

        playlists.addSongs(id, listOf(songId("b")))
        playlists.rename(id, "Mix 2")
        syncer.syncAdding(db, nd)
        assertEquals(listOf("A", "B"), titles(id))

        assertTrue(processor().process())
        assertEquals(listOf("replace pl1 Mix 2 [a, b]"), nd.calls)

        // Der eigene, gesendete Stand gilt beim nächsten Abgleich nicht als Server-Änderung.
        syncer.syncAdding(db, nd)
        assertEquals("Mix 2", playlists.playlists().first().single().name)
        assertEquals(listOf("A", "B"), titles(id))
    }

    @Test
    fun `server change wins over an unsent local change`() = runTest {
        val remoteId = nd.putPlaylist("Mix", listOf("a"))
        syncer.syncAdding(db, nd)
        val id = playlists.playlists().first().single().id
        playlists.addSongs(id, listOf(songId("b")))

        nd.putPlaylist("Vom Nachbarn", listOf("c"), remoteId)
        syncer.syncAdding(db, nd)

        assertEquals("Vom Nachbarn", playlists.playlists().first().single().name)
        assertEquals(listOf("C"), titles(id))
        assertEquals(0, db.syncDao().countActions())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `server change over an unsent local change is announced`() = runTest {
        val remoteId = nd.putPlaylist("Mix", listOf("a"))
        syncer.syncAdding(db, nd)
        val id = playlists.playlists().first().single().id
        val received = mutableListOf<SyncNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { notices.notices.toList(received) }

        // Ohne ausstehende Änderung gibt es nichts zu melden.
        nd.putPlaylist("Vom Nachbarn", listOf("b"), remoteId)
        syncer.syncAdding(db, nd)
        assertTrue(received.isEmpty())

        playlists.addSongs(id, listOf(songId("c")))
        nd.putPlaylist("Noch einmal", listOf("a"), remoteId)
        syncer.syncAdding(db, nd)

        assertEquals(listOf<SyncNotice>(SyncNotice.PlaylistReplacedByServer("Noch einmal")), received)
        job.cancel()
    }

    @Test
    fun `server change noticed while sending wins as well`() = runTest {
        val remoteId = nd.putPlaylist("Mix", listOf("a"))
        syncer.syncAdding(db, nd)
        val id = playlists.playlists().first().single().id
        playlists.addSongs(id, listOf(songId("b")))
        nd.putPlaylist("Mix", listOf("c", "a"), remoteId)

        assertTrue(processor().process())

        assertTrue(nd.calls.isEmpty())
        assertEquals(listOf("C", "A"), titles(id))
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `a change whose sending broke off wins over the half written server playlist`() = runTest {
        val remoteId = nd.putPlaylist("Mix", listOf("a", "b"))
        syncer.syncAdding(db, nd)
        val id = playlists.playlists().first().single().id
        playlists.addSongs(id, listOf(songId("c")))
        // Emby: Der erste Versuch hat schon Einträge entfernt und ist dann abgebrochen.
        db.syncDao().getActions().forEach { db.syncDao().incrementAttempts(it.actionId) }
        nd.putPlaylist("Mix", listOf("a"), remoteId)

        syncer.syncAdding(db, nd)
        assertEquals(listOf("A", "B", "C"), titles(id))

        assertTrue(processor().process())
        assertEquals(listOf("replace pl1 Mix [a, b, c]"), nd.calls)
        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `playlist deleted on the server disappears`() = runTest {
        val remoteId = nd.putPlaylist("Mix", listOf("a"))
        syncer.syncAdding(db, nd)

        nd.playlists.remove(remoteId)
        syncer.syncAdding(db, nd)

        assertTrue(playlists.playlists().first().isEmpty())
    }

    @Test
    fun `playlist created in the app is created on the server and deleted there`() = runTest {
        val id = playlists.create("Neu", "nd", listOf(songId("a"), songId("c")))
        assertTrue(processor().process())
        assertEquals(listOf("create Neu [a, c]"), nd.calls)

        // Der nächste Abgleich erkennt sie wieder und legt sie nicht doppelt an.
        syncer.syncAdding(db, nd)
        assertEquals(listOf(id), playlists.playlists().first().map { it.id })

        playlists.delete(id)
        assertTrue(processor().process())
        assertEquals("delete pl1", nd.calls.last())
        assertTrue(nd.playlists.isEmpty())
    }

    @Test
    fun `playlist deleted before it was sent is never sent`() = runTest {
        val id = playlists.create("Neu", "nd", listOf(songId("a")))
        playlists.delete(id)

        assertEquals(0, db.syncDao().countActions())
    }

    @Test
    fun `local playlist stays on the device`() = runTest {
        val id = playlists.create("Nur hier", null, listOf(songId("a")))
        playlists.addSongs(id, listOf(songId("b")))
        playlists.rename(id, "Nur hier!")

        assertEquals(0, db.syncDao().countActions())
        assertEquals(null, playlists.playlists().first().single().serverName)
    }

    @Test
    fun `songs missing on the server are marked and left out when sending`() = runTest {
        syncer.syncAdding(db, FakeLocalSource(listOf(track("1", "Nur lokal", 9))))
        val localSongId = db.syncDao().getSongSource("local:1")!!.songId

        val id = playlists.create("Neu", "nd", listOf(songId("a"), localSongId))

        assertEquals(listOf(false, true), playlists.playlist(id).first()!!.entries.map { it.missingOnServer })
        assertTrue(processor().process())
        assertEquals(listOf("create Neu [a]"), nd.calls)
    }

    private fun processor() = db.syncQueueProcessor(nd, notices = notices)

    private suspend fun songId(remoteId: String) = db.syncDao().getSongSource("nd:$remoteId")!!.songId

    private suspend fun titles(playlistId: Long) = playlists.playlist(playlistId).first()!!.entries.map { it.song.title }

    private fun track(remoteId: String, title: String, trackNumber: Int) = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = "Band",
        artists = listOf(RawArtist("Band")),
        albumTitle = "Album",
        albumArtistDisplay = "Band",
        albumArtists = listOf(RawArtist("Band")),
        trackNumber = trackNumber,
        durationMs = 100_000,
        lastModified = 1,
    )
}
