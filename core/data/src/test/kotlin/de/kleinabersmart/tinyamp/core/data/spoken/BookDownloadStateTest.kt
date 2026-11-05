package de.kleinabersmart.tinyamp.core.data.spoken

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.BookDownloadRow
import de.kleinabersmart.tinyamp.core.database.entity.BookEntity
import de.kleinabersmart.tinyamp.core.database.entity.BookTrackEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.model.TrackDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDownloadStateTest {

    private lateinit var db: TinyAmpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(total: Int = 2, done: Int = 0, running: Int = 0, queued: Int = 0, failed: Int = 0, removed: Int = 0) =
        BookDownloadRow(1, total, done, running, queued, failed, removed, bytes = 0)

    private suspend fun addBook(kind: BookKind, remoteId: String, files: Int): Long {
        val id = db.bookDao().insertBook(
            BookEntity(serverId = "s", kind = kind, remoteId = remoteId, title = remoteId, normTitle = remoteId, author = "", durationMs = 1000),
        )
        db.bookDao().insertTracks((0 until files).map { BookTrackEntity(id, it, "file$it", it * 500L, 500) })
        return id
    }

    @Test
    fun `a book is done only when every file is done`() {
        assertEquals(TrackDownload.DONE, row(done = 2).state())
        assertEquals(TrackDownload.QUEUED, row(done = 1, queued = 1).state())
        assertEquals(TrackDownload.RUNNING, row(running = 1, queued = 1).state())
    }

    @Test
    fun `one failed file fails the book and removed files count as removed`() {
        assertEquals(TrackDownload.FAILED, row(done = 1, failed = 1).state())
        assertEquals(TrackDownload.REMOVED, row(removed = 2).state())
        assertEquals(TrackDownload.NONE, row().state())
    }

    @Test
    fun `the episode queue never picks up a book and the book queue never picks up an episode`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 2)
        val episode = addBook(BookKind.EPISODE, "e", files = 1)

        assertEquals(2, db.bookDao().queueBookDownload(book))
        db.podcastDao().queueDownload(episode)

        assertEquals(episode, db.podcastDao().nextQueuedDownload()!!.bookId)
        assertEquals(1, db.podcastDao().countPendingDownloads())
        assertEquals(book, db.bookDao().nextQueuedBookTrack()!!.bookId)
        assertEquals(2, db.bookDao().countPendingBookDownloads())
    }

    @Test
    fun `files load one after the other and a failure stops the rest of the book`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 3)
        db.bookDao().queueBookDownload(book)

        assertEquals(0, db.bookDao().nextQueuedBookTrack()!!.trackIndex)
        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackDone(book, 0, "/x/book-$book-0.m4b", 400)
        assertEquals(1, db.bookDao().nextQueuedBookTrack()!!.trackIndex)

        db.bookDao().markBookTrackRunning(book, 1)
        db.bookDao().markBookDownloadFailed(book)

        assertNull(db.bookDao().nextQueuedBookTrack())
        val state = db.bookDao().observeBookDownloads().first().single().state()
        assertEquals(TrackDownload.FAILED, state)
        // Die fertige Datei bleibt, ein neuer Versuch lädt nur die fehlenden.
        assertEquals(2, db.bookDao().queueBookDownload(book))
        assertEquals(1, db.bookDao().nextQueuedBookTrack()!!.trackIndex)
    }

    @Test
    fun `removing a book resets its files and the heard cleanup of episodes ignores books`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 1)
        db.bookDao().queueBookDownload(book)
        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackDone(book, 0, "/x/book-$book-0.m4b", 400)
        db.bookDao().upsertProgress(
            de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity(book, 1000, 1000, finished = true, speed = 1f, updatedAt = 1),
        )

        assertEquals(emptyList<Any>(), db.podcastDao().observeFinishedDownloads().first())

        db.bookDao().markBookDownloadRemoved(book)
        assertEquals(TrackDownload.REMOVED, db.bookDao().observeBookDownloads().first().single().state())
        assertNull(db.bookDao().tracks(book).single().filePath)
    }
}
