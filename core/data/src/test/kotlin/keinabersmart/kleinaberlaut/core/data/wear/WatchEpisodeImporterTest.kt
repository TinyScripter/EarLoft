package keinabersmart.kleinaberlaut.core.data.wear

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchEpisodeImporterTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var importer: WatchEpisodeImporter
    private var episodeId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.podcastDao().upsertFeed(PodcastFeedEntity(FEED_ID, FEED_URL, "Podcast"))
        episodeId = db.bookDao().insertBook(
            BookEntity(serverId = FEED_ID, kind = BookKind.EPISODE, remoteId = GUID, title = "Folge", normTitle = "folge", author = "Podcast", durationMs = 3_600_000),
        )
        val stats = StatsRepository(db.statsDao(), db.bookDao(), db.podcastDao())
        importer = WatchEpisodeImporter(ApplicationProvider.getApplicationContext(), db.bookDao(), stats)
    }

    @After
    fun tearDown() = db.close()

    private fun progress(positionMs: Long, updatedAt: Long, finished: Boolean = false) =
        WatchEpisodeProgress(FEED_ID, GUID, positionMs, 3_600_000, finished, speed = 1.5f, updatedAt = updatedAt)

    @Test
    fun `neuerer Stand der Uhr gewinnt`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(episodeId, 60_000, 3_600_000, updatedAt = 1_000))

        assertEquals(1, importer.import("p1", listOf(progress(600_000, updatedAt = 2_000, finished = true)), emptyList()))

        val stored = db.bookDao().progress(episodeId)!!
        assertEquals(600_000, stored.positionMs)
        assertTrue(stored.finished)
        assertEquals(1.5f, stored.speed)
        assertEquals(2_000, stored.updatedAt)
    }

    @Test
    fun `aelterer Stand der Uhr wird verworfen`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(episodeId, 60_000, 3_600_000, updatedAt = 5_000))

        assertEquals(0, importer.import("p1", listOf(progress(600_000, updatedAt = 4_000)), emptyList()))

        assertEquals(60_000, db.bookDao().progress(episodeId)!!.positionMs)
    }

    @Test
    fun `gehoerte Zeit zaehlt einmal je Paket`() = runTest {
        val listening = listOf(WatchEpisodeListening(FEED_ID, GUID, listenedMs = 90_000, at = 1_000))

        importer.import("p1", emptyList(), listening)
        importer.import("p1", emptyList(), listening)
        importer.import("p2", emptyList(), listening)

        val row = db.statsDao().all().single()
        assertEquals(FEED_URL, row.groupKey)
        assertEquals(GUID, row.itemKey)
        assertEquals(180_000, row.listenedMs)
    }

    @Test
    fun `unbekannte Folgen werden uebersprungen`() = runTest {
        val unknown = WatchEpisodeProgress(FEED_ID, "andere", 1, 2, false, 1f, 3)
        assertEquals(0, importer.import("p1", listOf(unknown), listOf(WatchEpisodeListening(FEED_ID, "andere", 5_000, 1))))
        assertTrue(db.statsDao().all().isEmpty())
    }

    private companion object {
        const val FEED_ID = "feed-1"
        const val FEED_URL = "https://example.org/feed.xml"
        const val GUID = "guid-A"
    }
}
