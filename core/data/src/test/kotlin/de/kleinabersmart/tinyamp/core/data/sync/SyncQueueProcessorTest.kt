package de.kleinabersmart.tinyamp.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.tinyamp.core.data.repository.SyncQueuePlayReporter
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.AlbumEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongSourceEntity
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.RemoteLibraryActions
import de.kleinabersmart.tinyamp.core.datasource.RemotePlaybackReporting
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.model.SyncActionType
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SyncQueueProcessorTest {

    private lateinit var db: TinyAmpDatabase
    private val home = FakeServer("home")
    private val office = FakeServer("office")
    private var songId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.sourceDao().upsert(SourceEntity("local", SourceType.LOCAL, "local"))
        db.sourceDao().upsert(SourceEntity("home", SourceType.SUBSONIC, "home", priority = 1))
        db.sourceDao().upsert(SourceEntity("office", SourceType.SUBSONIC, "office"))
        val albumId = db.libraryDao().insertAlbum(
            AlbumEntity(title = "A", sortTitle = "A", normTitle = "a", albumArtistDisplay = "B", normAlbumArtist = "b"),
        )
        songId = db.libraryDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        listOf("local" to "1", "home" to "h", "office" to "o").forEach { (sourceId, remoteId) ->
            db.libraryDao().insertSongSource(
                SongSourceEntity("$sourceId:$remoteId", songId, sourceId, remoteId, seenInGeneration = 1),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sent favorite is removed from the queue and remembered as starred`() = runTest {
        db.syncDao().replaceAction("home", SyncActionType.SET_FAVORITE, "home:h", "true")

        assertTrue(processor().process())

        assertEquals(listOf("favorite h true"), home.calls)
        assertEquals(0, db.syncDao().countActions())
        assertNotNull(db.syncDao().getSongSource("home:h")!!.starredAt)
    }

    @Test
    fun `unreachable server keeps its actions while others are sent`() = runTest {
        home.failure = IOException("Verbindung abgelehnt")
        db.syncDao().replaceAction("home", SyncActionType.SET_FAVORITE, "home:h", "true")
        db.syncDao().replaceAction("office", SyncActionType.SET_FAVORITE, "office:o", "true")

        assertFalse(processor().process())

        assertEquals(listOf("favorite o true"), office.calls)
        val left = db.syncDao().getActions().single()
        assertEquals("home:h", left.targetKey)
        assertEquals(1, left.attempts)
    }

    @Test
    fun `action rejected by the server is dropped`() = runTest {
        home.failure = SubsonicException(SubsonicException.NOT_FOUND, "Song not found")
        db.syncDao().replaceAction("home", SyncActionType.SET_FAVORITE, "home:h", "true")

        assertTrue(processor().process())
        assertEquals(0, db.syncDao().countActions())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dropped favorite is announced but a dropped scrobble is not`() = runTest {
        home.failure = SubsonicException(SubsonicException.NOT_FOUND, "Song not found")
        db.syncDao().replaceAction("home", SyncActionType.SCROBBLE, "home:h", "1")
        db.syncDao().replaceAction("home", SyncActionType.SET_FAVORITE, "home:h", "true")
        val notices = SyncNotices()
        val received = mutableListOf<SyncNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { notices.notices.toList(received) }

        assertTrue(db.syncQueueProcessor(home, office, notices = notices).process())

        assertEquals(listOf<SyncNotice>(SyncNotice.ChangeDropped(SyncNotice.ChangeDropped.Kind.FAVORITE)), received)
        job.cancel()
    }

    @Test
    fun `plays are scrobbled once to the preferred server with the start time`() = runTest {
        val repository = PlaybackStateRepository(db.playbackDao(), reporter(home, office))

        repository.recordPlay(songId, playedAt = 100_000, listenedMs = 40_000)
        assertTrue(processor().process())

        // „home“ hat die höhere Priorität; die lokale Quelle zählt nicht.
        assertEquals(listOf("scrobble h 60000"), home.calls)
        assertTrue(office.calls.isEmpty())
    }

    @Test
    fun `a play that just ended goes live to a server that takes it and skips the queue`() = runTest {
        val live = LiveServer(home)

        reporter(live, office).report(songId, playedAt = System.currentTimeMillis(), listenedMs = 40_000)

        assertEquals(listOf("live h 1"), live.reports)
        assertTrue(db.syncQueueProcessor(live, office).process())
        assertTrue(home.calls.isEmpty())
    }

    @Test
    fun `a play the server could not take live is queued with its start time`() = runTest {
        val live = LiveServer(home).apply { failure = IOException("offline") }
        val playedAt = System.currentTimeMillis()

        reporter(live, office).report(songId, playedAt, listenedMs = 40_000)

        assertTrue(db.syncQueueProcessor(live, office).process())
        assertEquals(listOf("scrobble h ${playedAt - 40_000}"), home.calls)
    }

    @Test
    fun `an older play, e g from the watch, is queued instead of reported live`() = runTest {
        val live = LiveServer(home)
        val playedAt = System.currentTimeMillis() - SyncQueuePlayReporter.LIVE_WINDOW_MS - 60_000

        reporter(live, office).report(songId, playedAt, listenedMs = 40_000)

        assertTrue(live.reports.isEmpty())
        assertTrue(db.syncQueueProcessor(live, office).process())
        assertEquals(listOf("scrobble h ${playedAt - 40_000}"), home.calls)
    }

    @Test
    fun `a play passed on from the watch is queued even when it just ended`() = runTest {
        val live = LiveServer(home)
        val playedAt = System.currentTimeMillis()

        reporter(live, office).report(songId, playedAt, listenedMs = 40_000, live = false)

        assertTrue(live.reports.isEmpty())
        assertTrue(db.syncQueueProcessor(live, office).process())
        assertEquals(listOf("scrobble h ${playedAt - 40_000}"), home.calls)
    }

    @Test
    fun `a play is in the queue while it is being reported live`() = runTest {
        var queuedDuringReport = -1
        val live = LiveServer(home).apply { onReport = { queuedDuringReport = db.syncDao().countActions() } }

        // Echte Zeit: Die Room-Abfrage in der Meldung liefe sonst gegen die virtuelle Zeitgrenze von 10 s.
        withContext(Dispatchers.IO) {
            reporter(live, office).report(songId, playedAt = System.currentTimeMillis(), listenedMs = 40_000)
        }

        // Endet der Prozess während der Meldung, bleibt sie so erhalten; danach ist sie wieder weg.
        assertEquals(1, queuedDuringReport)
        assertEquals(0, db.syncDao().countActions())
    }

    private fun reporter(vararg sources: MusicDataSource) = SyncQueuePlayReporter(
        libraryDao = db.libraryDao(),
        mergeDao = db.mergeDao(),
        syncDao = db.syncDao(),
        syncSettings = SyncSettings(ApplicationProvider.getApplicationContext()),
        syncScheduler = NoScheduler,
        sources = FixedSources(*sources),
    )

    /** Wie [FakeServer], nimmt aber Wiedergaben live an (Jellyfin). */
    private class LiveServer(private val server: FakeServer) :
        MusicDataSource by server, RemoteLibraryActions by server, RemotePlaybackReporting {
        val reports = mutableListOf<String>()
        var failure: Exception? = null
        var onReport: suspend () -> Unit = {}

        override suspend fun reportPlayback(remoteId: String, durationMs: Long) {
            onReport()
            failure?.let { throw it }
            reports += "live $remoteId $durationMs"
        }
    }

    private fun processor() = db.syncQueueProcessor(home, office)
}
