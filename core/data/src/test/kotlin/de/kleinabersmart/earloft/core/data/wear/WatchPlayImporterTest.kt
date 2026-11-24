package de.kleinabersmart.earloft.core.data.wear

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.PlayReporter
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchPlayImporterTest {

    private class RecordingReporter : PlayReporter {
        val reported = mutableListOf<Triple<Long, Long, Long>>()
        var failAt: Long? = null
        override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean) {
            if (playedAt == failAt) error("aborted")
            reported += Triple(songId, playedAt, listenedMs)
        }
    }

    /** Counts the plays the sync queue would find when it is started. */
    private inner class VisiblePlays : SyncScheduler {
        val seen = mutableListOf<Int>()
        override suspend fun enqueue() {
            seen += db.query("SELECT COUNT(*) FROM play_events", null).use { it.moveToFirst(); it.getInt(0) }
        }
    }

    private lateinit var db: EarLoftDatabase
    private lateinit var importer: WatchPlayImporter
    private val reporter = RecordingReporter()
    private val scheduler = VisiblePlays()
    private var songId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.sourceDao().upsert(SourceEntity("home", SourceType.SUBSONIC, "home"))
        val albumId = db.mergeDao().insertAlbum(
            AlbumEntity(title = "A", sortTitle = "A", normTitle = "a", albumArtistDisplay = "B", normAlbumArtist = "b"),
        )
        songId = db.mergeDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        db.mergeDao().insertSongSource(SongSourceEntity("home:h", songId, "home", "h", seenInGeneration = 1))
        importer = WatchPlayImporter(
            ApplicationProvider.getApplicationContext(),
            db,
            db.wearDao(),
            PlaybackStateRepository(db.playbackDao(), reporter),
            scheduler,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `plays enter the history and are reported with their own time`() = runTest {
        val imported = importer.import("p1", listOf(WatchPlay("home:h", playedAt = 1_000, listenedMs = 400)))

        assertEquals(1, imported)
        assertEquals(listOf(Triple(songId, 1_000L, 400L)), reporter.reported)
    }

    @Test
    fun `the sync queue starts once after the packet's transaction, with its plays in place`() = runTest {
        importer.import("p1", listOf(WatchPlay("home:h", 1, 1), WatchPlay("home:h", 2, 2)))
        importer.import("p2", listOf(WatchPlay("gone:x", 3, 3)))

        // review3 Q11: started inside the transaction, a pass could run before the commit and find nothing.
        assertEquals(listOf(2), scheduler.seen)
    }

    @Test
    fun `unknown songs are skipped`() = runTest {
        val imported = importer.import("p1", listOf(WatchPlay("gone:x", 1, 1), WatchPlay("home:h", 2, 2)))

        assertEquals(1, imported)
        assertEquals(listOf(Triple(songId, 2L, 2L)), reporter.reported)
    }

    @Test
    fun `a packet that arrives twice is imported once`() = runTest {
        val plays = listOf(WatchPlay("home:h", 1, 1))

        assertEquals(1, importer.import("p1", plays))
        assertEquals(0, importer.import("p1", plays))
        assertEquals(1, reporter.reported.size)
    }

    @Test
    fun `a packet aborted halfway counts its first plays only once when it arrives again`() = runTest {
        val plays = listOf(WatchPlay("home:h", 1, 1), WatchPlay("home:h", 2, 2))
        reporter.failAt = 2
        runCatching { importer.import("p1", plays) }
        reporter.failAt = null

        assertEquals(2, importer.import("p1", plays))
        assertEquals(listOf(1L, 2L), db.query("SELECT playedAt FROM play_events ORDER BY playedAt", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        })
    }
}
