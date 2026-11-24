package de.kleinabersmart.earloft.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The states of a song download: only the expected transitions apply, and a removed entry stops a running transfer. */
@RunWith(RobolectricTestRunner::class)
class DownloadDaoTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var dao: DownloadDao
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadDao()
        db.sourceDao().upsert(SourceEntity("nd", SourceType.SUBSONIC, "Navidrome"))
        db.sourceDao().upsert(SourceEntity("nd2", SourceType.SUBSONIC, "Second Navidrome"))
        db.sourceDao().upsert(SourceEntity("phone", SourceType.PHONE, "Phone"))
        albumId = db.mergeDao().insertAlbum(AlbumEntity(title = "Album", sortTitle = "Album", normTitle = "album", albumArtistDisplay = "A", normAlbumArtist = "a"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A queued download of a new song from [sourceId]. */
    private suspend fun queued(sourceId: String, remoteId: String): String {
        val songId = db.mergeDao().insertSong(SongEntity(albumId = albumId, title = remoteId, normTitle = remoteId, artistDisplay = "A", trackNumber = 1, durationMs = 1_000, dateAdded = 0))
        val uid = "$sourceId:$remoteId"
        db.mergeDao().insertSongSource(SongSourceEntity(uid = uid, songId = songId, sourceId = sourceId, remoteId = remoteId, seenInGeneration = 1))
        dao.insertIfAbsent(listOf(DownloadEntity(sourceUid = uid, songId = songId, state = DownloadState.QUEUED)))
        return uid
    }

    private suspend fun state(uid: String) = dao.get(uid)?.state

    @Test
    fun `a download runs once and only a running one is finished`() = runTest {
        val uid = queued("nd", "s1")

        assertEquals(1, dao.markRunning(uid))
        assertEquals("already running", 0, dao.markRunning(uid))
        assertEquals(1, dao.updateProgress(uid, 500, 1_000))
        assertEquals(1, dao.markDone(uid, "/music/s1.flac", 1_000))

        val done = dao.get(uid)!!
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(1_000L, done.bytesDone)
        assertEquals("/music/s1.flac", done.filePath)
        dao.requeue(uid)
        assertEquals("a finished one stays finished", DownloadState.DONE, state(uid))
    }

    @Test
    fun `a download removed during the transfer reports it to the worker`() = runTest {
        val uid = queued("nd", "s1")
        dao.markRunning(uid)

        dao.delete(listOf(uid))

        assertEquals(0, dao.updateProgress(uid, 500, 1_000))
        assertEquals(0, dao.markDone(uid, "/music/s1.flac", 1_000))
        assertNull(dao.get(uid))
    }

    @Test
    fun `downloads stuck after a process death go back into the queue at zero`() = runTest {
        val running = queued("nd", "s1")
        val done = queued("nd", "s2")
        dao.markRunning(running)
        dao.updateProgress(running, 700, 1_000)
        dao.markRunning(done)
        dao.markDone(done, "/music/s2.flac", 1_000)

        dao.requeueRunning()

        assertEquals(DownloadState.QUEUED, state(running))
        assertEquals(0L, dao.get(running)!!.bytesDone)
        assertEquals(DownloadState.DONE, state(done))
    }

    @Test
    fun `the watch takes server downloads in queue order, without the phone's and the skipped ones`() = runTest {
        val fromPhone = queued("phone", "p1")
        val first = queued("nd", "s1")
        val second = queued("nd", "s2")

        assertEquals(first, dao.nextQueuedFromServers(emptyList())!!.sourceUid)
        assertEquals(second, dao.nextQueuedFromServers(listOf(first))!!.sourceUid)
        assertNull(dao.nextQueuedFromServers(listOf(first, second)))
        assertEquals(fromPhone, dao.nextQueuedFromPhone()!!.sourceUid)
        assertEquals(fromPhone, dao.nextQueued()!!.sourceUid)
    }

    @Test
    fun `rejected credentials fail only that server's waiting downloads`() = runTest {
        val mine = queued("nd", "s1")
        val similar = queued("nd2", "s1")
        val running = queued("nd", "s2")
        dao.markRunning(running)

        assertEquals(1, dao.failQueuedOfSource("nd"))

        assertEquals(DownloadState.FAILED, state(mine))
        assertEquals("a server whose id starts the same is not touched", DownloadState.QUEUED, state(similar))
        assertEquals(DownloadState.RUNNING, state(running))

        assertEquals(1, dao.retryFailedOfSource("nd"))
        assertEquals(DownloadState.QUEUED, state(mine))
    }
}
