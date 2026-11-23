package de.kleinabersmart.earloft.core.download

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.HttpStatusException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Runs of the [BookDownloadWorker] on the phone, with the real database, files and [DownloadSpace]; only the server and the
 * follow-up job are fakes. Covers running out of room on mobile data (review2 J3), the hand-over to Wi-Fi and the retry budget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BookDownloadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.bookDao()
    private val storage = BookStorage(context)
    private val space = DownloadSpace(context)
    private val edges = FakeEdges()

    private val entryPoint = object : BookDownloadWorker.BookEntryPoint {
        override fun bookDao() = dao
        override fun bookStorage() = storage
        override fun bookProgress() = BookDownloadProgress()
        override fun space() = space
        override fun bookEdges(): BookDownloadEdges = edges
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `running out of room on mobile data leaves the queue to the space retry, without a follow-up job`() = runTest {
        val bookId = book(files = 2, auto = listOf(false, true))
        edges.server = { DownloadOutcome.NoSpace(totalBytes = 1_658_275_016) }

        assertEquals(ListenableWorker.Result.success(), runWorker(manualOnly = true))

        assertEquals(listOf(0), edges.calls)
        assertEquals("both files stay queued", List(2) { TrackDownload.QUEUED }, states(bookId))
        assertEquals("no Wi-Fi job that would hit the same wall", 0, edges.handOvers)
        assertTrue(space.isShort.value)
        assertTrue("the space retry takes over", enqueued("download-space-retry-BOOKS"))
    }

    @Test
    fun `a mobile run downloads only the manual files and hands the rest to Wi-Fi`() = runTest {
        val bookId = book(files = 3, auto = listOf(false, false, true))

        runWorker(manualOnly = true)

        assertEquals(listOf(0, 1), edges.calls)
        assertEquals(listOf(TrackDownload.DONE, TrackDownload.DONE, TrackDownload.QUEUED), states(bookId))
        assertEquals(1, edges.handOvers)
        assertFalse(space.isShort.value)
    }

    @Test
    fun `a Wi-Fi run takes every file and hands nothing over`() = runTest {
        val bookId = book(files = 2, auto = listOf(false, true))

        runWorker(manualOnly = false)

        assertEquals(listOf(0, 1), edges.calls)
        assertEquals(List(2) { TrackDownload.DONE }, states(bookId))
        assertEquals(0, edges.handOvers)
    }

    @Test
    fun `an interrupted file is queued again and the run retried while attempts are left`() = runTest {
        val bookId = book(files = 2)
        edges.server = { DownloadOutcome.Retry(IOException("Connection reset")) }

        assertEquals(ListenableWorker.Result.retry(), runWorker(attempt = 0))

        assertEquals(listOf(0), edges.calls)
        assertEquals(List(2) { TrackDownload.QUEUED }, states(bookId))
    }

    @Test
    fun `in the last attempt only the first interrupted file gives up, the rest waits`() = runTest {
        val bookId = book(files = 3)
        edges.server = { DownloadOutcome.Retry(IOException("Connection reset")) }

        assertEquals(ListenableWorker.Result.retry(), runWorker(attempt = 4))

        assertEquals(listOf(0, 1), edges.calls)
        assertEquals(listOf(TrackDownload.FAILED, TrackDownload.QUEUED, TrackDownload.QUEUED), states(bookId))
    }

    @Test
    fun `a rejected key fails the whole book right away`() = runTest {
        val bookId = book(files = 3)
        edges.server = { DownloadOutcome.Retry(HttpStatusException(401)) }

        assertEquals(ListenableWorker.Result.success(), runWorker(attempt = 0))

        assertEquals("no request per file", listOf(0), edges.calls)
        assertEquals(List(3) { TrackDownload.FAILED }, states(bookId))
    }

    private suspend fun runWorker(manualOnly: Boolean = false, attempt: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder<BookDownloadWorker>(context)
            .setInputData(workDataOf(BookDownloadWorker.KEY_MANUAL_ONLY to manualOnly))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    BookDownloadWorker(appContext, workerParameters, entryPoint)
            })
            .build()
            .doWork()

    private fun enqueued(uniqueName: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().any { it.state == WorkInfo.State.ENQUEUED }

    private suspend fun book(files: Int, auto: List<Boolean> = List(files) { false }): Long {
        val bookId = dao.insertBook(
            BookEntity(serverId = "abs", remoteId = "li-1", title = "Book", normTitle = "book", author = "Author", durationMs = files * 60_000L),
        )
        dao.insertTracks(
            List(files) { index ->
                BookTrackEntity(
                    bookId = bookId,
                    trackIndex = index,
                    remoteFileId = "ino-$index",
                    startMs = index * 60_000L,
                    durationMs = 60_000,
                    mimeType = "audio/mpeg",
                    downloadState = TrackDownload.QUEUED,
                    autoDownload = auto[index],
                )
            },
        )
        return bookId
    }

    private suspend fun states(bookId: Long) = dao.tracks(bookId).map { it.downloadState }

    /** The server; like [BookDownloader] it marks the file running and, when done, downloaded. */
    private inner class FakeEdges : BookDownloadEdges {
        var server: (BookTrackEntity) -> DownloadOutcome = { DownloadOutcome.Done }
        val calls = mutableListOf<Int>()
        var handOvers = 0

        override suspend fun download(track: BookTrackEntity, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
            calls += track.trackIndex
            if (dao.markBookTrackRunning(track.bookId, track.trackIndex) == 0) return DownloadOutcome.Cancelled
            val outcome = server(track)
            if (outcome == DownloadOutcome.Done) {
                val file = storage.fileFor(track.bookId, track.trackIndex, track.mimeType)
                file.parentFile?.mkdirs()
                file.writeBytes(ByteArray(16))
                dao.markBookTrackDone(track.bookId, track.trackIndex, file.absolutePath, file.length())
            }
            return outcome
        }

        override suspend fun continueOnWifi() {
            handOvers++
        }
    }
}
