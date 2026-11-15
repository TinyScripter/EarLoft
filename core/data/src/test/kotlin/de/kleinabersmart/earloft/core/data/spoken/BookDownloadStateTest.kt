package de.kleinabersmart.earloft.core.data.spoken

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDownloadRow
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDownloadStateTest {

    private lateinit var db: EarLoftDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
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
    fun `a failed file shows only once the rest has loaded and removed files count as removed`() {
        assertEquals(TrackDownload.FAILED, row(done = 1, failed = 1).state())
        assertEquals(TrackDownload.RUNNING, row(total = 3, done = 1, running = 1, failed = 1).state())
        assertTrue(row(done = 1, failed = 1).toDownload().partial)
        assertFalse(row(failed = 2).toDownload().partial)
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
    fun `a failed file leaves the others of the book loading`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 3)
        db.bookDao().queueBookDownload(book)

        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackFailed(book, 0)

        assertEquals(1, db.bookDao().nextQueuedBookTrack()!!.trackIndex)
        db.bookDao().markBookTrackRunning(book, 1)
        db.bookDao().markBookTrackDone(book, 1, "/x/book-$book-1.m4b", 400)
        db.bookDao().markBookTrackRunning(book, 2)
        db.bookDao().markBookTrackDone(book, 2, "/x/book-$book-2.m4b", 400)

        val download = db.bookDao().observeBookDownloads().first().single().toDownload()
        assertEquals(TrackDownload.FAILED, download.state)
        assertTrue(download.partial)
        assertEquals(2 to 3, download.doneFiles to download.totalFiles)
        // „Erneut laden“ nimmt nur die fehlende Datei.
        assertEquals(1, db.bookDao().queueBookDownload(book))
        assertEquals(0, db.bookDao().nextQueuedBookTrack()!!.trackIndex)
    }

    @Test
    fun `new details from the server keep the download of unchanged files`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 2)
        db.bookDao().queueBookDownload(book)
        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackDone(book, 0, "/x/book-$book-0.m4b", 400)
        db.bookDao().markBookTrackRunning(book, 1)
        db.bookDao().markBookTrackDone(book, 1, "/x/book-$book-1.m4b", 400)

        // Metadaten geändert: dieselbe erste Datei, die zweite ist ersetzt (andere Länge).
        db.bookDao().replaceDetails(
            book,
            listOf(BookTrackEntity(book, 0, "file0", 0, 500), BookTrackEntity(book, 1, "file1", 500, 700)),
            emptyList(),
        )

        val tracks = db.bookDao().tracks(book)
        assertEquals(TrackDownload.DONE, tracks[0].downloadState)
        assertEquals("/x/book-$book-0.m4b", tracks[0].filePath)
        assertEquals(TrackDownload.NONE, tracks[1].downloadState)
        assertNull(tracks[1].filePath)
    }

    @Test
    fun `a file put in front on the server keeps its path and that path counts as taken for the new first file`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 2)
        db.bookDao().queueBookDownload(book)
        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackDone(book, 0, "/x/book-$book-0.m4b", 400)
        db.bookDao().markBookTrackRunning(book, 1)
        db.bookDao().markBookTrackDone(book, 1, "/x/book-$book-1.m4b", 400)

        // Auf dem Server kommt eine Datei vor die beiden: Die geladenen rücken auf 1 und 2 und behalten ihre Dateien.
        db.bookDao().replaceDetails(
            book,
            listOf(BookTrackEntity(book, 0, "intro", 0, 100), BookTrackEntity(book, 1, "file0", 100, 500), BookTrackEntity(book, 2, "file1", 600, 500)),
            emptyList(),
        )

        assertEquals("/x/book-$book-0.m4b", db.bookDao().tracks(book)[1].filePath)
        assertEquals(setOf("/x/book-$book-0.m4b", "/x/book-$book-1.m4b"), db.bookDao().otherTrackFiles(book, 0).toSet())
        assertEquals(listOf("/x/book-$book-1.m4b"), db.bookDao().otherTrackFiles(book, 1))
    }

    @Test
    fun `removing a book resets its files and the heard cleanup of episodes ignores books`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 1)
        db.bookDao().queueBookDownload(book)
        db.bookDao().markBookTrackRunning(book, 0)
        db.bookDao().markBookTrackDone(book, 0, "/x/book-$book-0.m4b", 400)
        db.bookDao().upsertProgress(
            de.kleinabersmart.earloft.core.database.entity.BookProgressEntity(book, 1000, 1000, finished = true, speed = 1f, updatedAt = 1),
        )

        assertEquals(emptyList<Any>(), db.podcastDao().observeFinishedDownloads().first())

        db.bookDao().markBookDownloadRemoved(book)
        assertEquals(TrackDownload.REMOVED, db.bookDao().observeBookDownloads().first().single().state())
        assertNull(db.bookDao().tracks(book).single().filePath)
    }

    @Test
    fun `files of the automatic download are not picked up by the download over mobile data`() = runTest {
        val automatic = addBook(BookKind.BOOK, "a", files = 2)
        val manual = addBook(BookKind.BOOK, "m", files = 1)
        assertEquals(2, db.bookDao().queueBookDownloadAutomatic(automatic))

        // Nur die Automatik wartet: Ein Auftrag über Mobilfunk hätte nichts zu tun, der Auftrag bleibt im WLAN.
        assertEquals(0, db.bookDao().countPendingManualBookDownloads())
        assertNull(db.bookDao().nextQueuedManualBookTrack())
        assertEquals(automatic, db.bookDao().nextQueuedBookTrack()!!.bookId)

        db.bookDao().queueBookDownload(manual)
        assertEquals(1, db.bookDao().countPendingManualBookDownloads())
        assertEquals(manual, db.bookDao().nextQueuedManualBookTrack()!!.bookId)
        // Im WLAN kommt zuerst, was der Nutzer selbst angestoßen hat, obwohl das Buch der Automatik vorher eingereiht war.
        assertEquals(manual, db.bookDao().nextQueuedBookTrack()!!.bookId)
        assertEquals(3, db.bookDao().countPendingBookDownloads())
    }

    @Test
    fun `downloading by hand takes over the files the automatic download already queued`() = runTest {
        val book = addBook(BookKind.BOOK, "b", files = 3)
        db.bookDao().queueBookDownloadAutomatic(book)
        db.bookDao().markBookTrackRunning(book, 0)

        // Die laufende und die wartenden Dateien gelten danach als von Hand und dürfen über Mobilfunk laden.
        assertEquals(3, db.bookDao().queueBookDownload(book))
        val tracks = db.bookDao().tracks(book)
        assertEquals(listOf(TrackDownload.RUNNING, TrackDownload.QUEUED, TrackDownload.QUEUED), tracks.map { it.downloadState })
        assertTrue(tracks.none { it.autoDownload })
        assertEquals(3, db.bookDao().countPendingManualBookDownloads())
        // Nichts mehr zu übernehmen: kein neuer Auftrag.
        assertEquals(0, db.bookDao().queueBookDownload(book))
    }
}
