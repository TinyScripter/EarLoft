package de.kleinabersmart.earloft.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.KeyedEpisodeDownload
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Episode files when a feed changes their address, removing a subscription, and which removed downloads the backup keeps. */
@RunWith(RobolectricTestRunner::class)
class PodcastDaoTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var dao: PodcastDao
    private lateinit var bookDao: BookDao

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.podcastDao()
        bookDao = db.bookDao()
        dao.upsertFeed(PodcastFeedEntity(feedId = "f1", url = "https://example.org/1.xml", title = "One"))
        dao.upsertFeed(PodcastFeedEntity(feedId = "f2", url = "https://example.org/2.xml", title = "Two"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun episode(feedId: String, guid: String, addedAt: Long = 0, state: TrackDownload = TrackDownload.NONE): Long {
        val bookId = bookDao.upsertBook(BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 600_000, addedAt = addedAt))
        dao.setTrack(track(bookId, "https://cdn.example.org/$guid.mp3"))
        if (state != TrackDownload.NONE) {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE book_tracks SET downloadState = '${state.name}', filePath = ${if (state == TrackDownload.DONE) "'/podcasts/$guid.mp3'" else "NULL"} WHERE bookId = $bookId",
            )
        }
        return bookId
    }

    private fun track(bookId: Long, url: String, durationMs: Long = 600_000) = BookTrackEntity(bookId, 0, url, 0, durationMs, "audio/mpeg")

    @Test
    fun `a downloaded episode keeps its file when the feed changes the address`() = runTest {
        val bookId = episode("f1", "e1", state = TrackDownload.DONE)

        dao.setTrack(track(bookId, "https://cdn.example.org/e1.mp3?token=new", durationMs = 610_000))

        val stored = dao.trackOf(bookId)!!
        assertEquals("https://cdn.example.org/e1.mp3?token=new", stored.remoteFileId)
        assertEquals(TrackDownload.DONE, stored.downloadState)
        assertEquals("/podcasts/e1.mp3", stored.filePath)
        assertEquals(610_000L, stored.durationMs)
    }

    @Test
    fun `a failed episode starts over with the new address`() = runTest {
        val bookId = episode("f1", "e1", state = TrackDownload.FAILED)

        dao.setTrack(track(bookId, "https://mirror.example.org/e1.mp3"))

        val stored = dao.trackOf(bookId)!!
        assertEquals("https://mirror.example.org/e1.mp3", stored.remoteFileId)
        assertEquals(TrackDownload.NONE, stored.downloadState)
    }

    @Test
    fun `a failed episode with the same address stays failed`() = runTest {
        val bookId = episode("f1", "e1", state = TrackDownload.FAILED)

        dao.setTrack(track(bookId, "https://cdn.example.org/e1.mp3"))

        assertEquals(TrackDownload.FAILED, dao.trackOf(bookId)!!.downloadState)
    }

    @Test
    fun `removing a subscription takes its episodes and their progress, not the others`() = runTest {
        val mine = episode("f1", "e1")
        val other = episode("f2", "e1")
        bookDao.upsertProgress(BookProgressEntity(mine, positionMs = 5_000, durationMs = 600_000, updatedAt = 1))
        bookDao.upsertProgress(BookProgressEntity(other, positionMs = 7_000, durationMs = 600_000, updatedAt = 1))

        dao.deleteFeed("f1")

        assertNull(dao.feed("f1"))
        assertNull(bookDao.book(mine))
        assertNull(bookDao.progress(mine))
        assertEquals(7_000L, bookDao.progress(other)!!.positionMs)
        assertEquals(listOf("f2"), dao.feeds().map { it.feedId })
    }

    @Test
    fun `the backup keeps removed downloads only after the last finished episode and at most twenty per feed`() = runTest {
        // Feed 1: e1 heard to the end; e0 before it was removed, e2..e30 after it.
        episode("f1", "e0", addedAt = 0, state = TrackDownload.REMOVED)
        val heard = episode("f1", "e1", addedAt = 1)
        bookDao.upsertProgress(BookProgressEntity(heard, positionMs = 600_000, durationMs = 600_000, finished = true, updatedAt = 1))
        (2..30).forEach { episode("f1", "e$it", addedAt = it.toLong(), state = TrackDownload.REMOVED) }
        episode("f1", "done", addedAt = 31, state = TrackDownload.DONE)
        episode("f1", "never", addedAt = 32)
        // Feed 2: nothing heard yet, so every removed one counts.
        episode("f2", "x1", addedAt = 5, state = TrackDownload.REMOVED)

        val kept = dao.keyedEpisodeDownloads()

        val removedOfFeed1 = kept.filter { it.serverId == "f1" && it.downloadState == TrackDownload.REMOVED }.map { it.remoteId }
        assertEquals((11..30).map { "e$it" }.toSet(), removedOfFeed1.toSet())
        assertTrue(KeyedEpisodeDownload("f1", "done", TrackDownload.DONE) in kept)
        assertTrue(kept.none { it.remoteId == "never" || it.remoteId == "e0" })
        assertTrue(KeyedEpisodeDownload("f2", "x1", TrackDownload.REMOVED) in kept)
    }

    @Test
    fun `cleaning up a trial does not count as a removal from the backup`() = runTest {
        dao.upsertFeed(PodcastFeedEntity(feedId = "trial", url = "https://example.org/trial.xml", title = "Trial", subscribed = false))
        val kept = episode("f1", "e1")
        val tried = episode("trial", "t1")
        listOf(kept, tried).forEach { bookDao.upsertProgress(BookProgressEntity(it, 1_000, 600_000, false, 1f, 1)) }
        val before = db.backupDao().rowCounts()

        dao.deleteFeed("trial")

        assertEquals(1, before.progress)
        assertTrue(!db.backupDao().rowCounts().shrankSince(before))
    }
}
