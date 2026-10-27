package keinabersmart.kleinaberlaut.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.SyncActionType
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicException
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

    private lateinit var db: KleinaberlautDatabase
    private val home = FakeServer("home")
    private val office = FakeServer("office")
    private var songId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
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

    @Test
    fun `plays are scrobbled once to the preferred server with the start time`() = runTest {
        val repository = PlaybackStateRepository(
            database = db,
            playbackDao = db.playbackDao(),
            libraryDao = db.libraryDao(),
            syncDao = db.syncDao(),
            syncSettings = SyncSettings(ApplicationProvider.getApplicationContext()),
            syncScheduler = NoScheduler,
        )

        repository.recordPlay(songId, playedAt = 100_000, listenedMs = 40_000)
        assertTrue(processor().process())

        // „home“ hat die höhere Priorität; die lokale Quelle zählt nicht.
        assertEquals(listOf("scrobble h 60000"), home.calls)
        assertTrue(office.calls.isEmpty())
    }

    private fun processor() = db.syncQueueProcessor(home, office)
}
