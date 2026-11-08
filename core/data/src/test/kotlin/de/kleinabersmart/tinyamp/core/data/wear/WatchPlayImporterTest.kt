package de.kleinabersmart.tinyamp.core.data.wear

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.data.repository.PlayReporter
import de.kleinabersmart.tinyamp.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.AlbumEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongSourceEntity
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.model.SourceType
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
        override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean) {
            reported += Triple(songId, playedAt, listenedMs)
        }
    }

    private lateinit var db: TinyAmpDatabase
    private lateinit var importer: WatchPlayImporter
    private val reporter = RecordingReporter()
    private var songId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.sourceDao().upsert(SourceEntity("home", SourceType.SUBSONIC, "home"))
        val albumId = db.libraryDao().insertAlbum(
            AlbumEntity(title = "A", sortTitle = "A", normTitle = "a", albumArtistDisplay = "B", normAlbumArtist = "b"),
        )
        songId = db.libraryDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        db.libraryDao().insertSongSource(SongSourceEntity("home:h", songId, "home", "h", seenInGeneration = 1))
        importer = WatchPlayImporter(
            ApplicationProvider.getApplicationContext(),
            db.wearDao(),
            PlaybackStateRepository(db.playbackDao(), reporter),
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
}
