package de.kleinabersmart.earloft.wear.download

import android.app.Application
import android.content.Context
import android.net.Network
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadProfile
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
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
import org.robolectric.shadows.ShadowNetwork
import java.io.IOException

/**
 * Runs of the [WearDownloadWorker] for an audiobook, with the real database, files and [WatchState]; only Wi-Fi, the
 * server and the phone are fakes ([FakeEdges]). Covers the failed attempts per book (review2 J6), the size of a file from
 * the phone that doesn't fit (J7) and a Wi-Fi that goes away (G30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearDownloadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
    private val bookDao = db.bookDao()
    private val state = WatchState(context)
    private val bookStorage = BookStorage(context)
    private val edges = FakeEdges()

    private val entryPoint = object : WearDownloadWorker.WearDownloadEntryPoint {
        override fun downloadDao() = db.downloadDao()
        override fun wearDao() = db.wearDao()
        override fun storage() = DownloadStorage(context)
        override fun state() = state
        override fun coverFiles() = CoverFiles(context)
        override fun scheduler() = WearDownloadScheduler(context, state)
        override fun podcastDao() = db.podcastDao()
        override fun episodeStorage() = EpisodeStorage(context)
        override fun bookDao() = bookDao
        override fun bookStorage() = bookStorage
        override fun edges(): WearDownloadEdges = edges
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
    fun `a book the server never delivers comes via the phone after three runs, all files in the same run`() = runTest {
        val bookId = book(files = 3)
        edges.server = { DownloadOutcome.Retry(IOException("Server unreachable")) }

        repeat(2) { run ->
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertEquals("run ${run + 1} asks the server once", List(run + 1) { 0 }, edges.serverCalls)
            assertTrue("run ${run + 1} doesn't ask the phone", edges.phoneCalls.isEmpty())
            assertTrue("run ${run + 1} schedules the next one", retryScheduled())
            assertEquals(List(3) { TrackDownload.QUEUED }, states(bookId))
        }

        runWorker()

        assertEquals("the third failure is the last server attempt", listOf(0, 0, 0), edges.serverCalls)
        assertEquals("then every file comes via the phone in this run", listOf(0, 1, 2), edges.phoneCalls)
        assertEquals(List(3) { TrackDownload.DONE }, states(bookId))
        assertFalse("the count ends with the book", state.hasServerFailedTooOften(failureKey(bookId)))
        assertEquals("a finished run starts the retries from the short interval", 0, state.retryRuns)
    }

    @Test
    fun `while the phone is away the book keeps its count and the next run asks the phone directly`() = runTest {
        val bookId = book(files = 2)
        edges.server = { DownloadOutcome.Retry(IOException("Server unreachable")) }
        edges.phone = { DownloadOutcome.Retry(IOException("Phone not connected")) }
        repeat(3) { runWorker() }
        assertEquals(listOf(0, 0, 0), edges.serverCalls)
        assertEquals(listOf(0), edges.phoneCalls)

        edges.phone = { DownloadOutcome.Done }
        runWorker()

        assertEquals("no fourth server attempt", 3, edges.serverCalls.size)
        assertEquals(listOf(0, 0, 1), edges.phoneCalls)
        assertEquals(List(2) { TrackDownload.DONE }, states(bookId))
    }

    @Test
    fun `a server that delivers again ends the count without the phone`() = runTest {
        val bookId = book(files = 2)
        edges.server = { DownloadOutcome.Retry(IOException("Timeout")) }
        runWorker()
        assertTrue(state.retryRuns > 0)

        edges.server = { DownloadOutcome.Done }
        runWorker()

        assertEquals(listOf(0, 0, 1), edges.serverCalls)
        assertTrue(edges.phoneCalls.isEmpty())
        assertEquals(List(2) { TrackDownload.DONE }, states(bookId))
        assertFalse(state.hasServerFailedTooOften(failureKey(bookId)))
        assertEquals(0, state.retryRuns)
    }

    @Test
    fun `a file from the phone that doesn't fit keeps its size and ends the run`() = runTest {
        val bookId = book(files = 3)
        edges.server = { DownloadOutcome.Retry(IOException("Server unreachable")) }
        edges.phone = { DownloadOutcome.NoSpace(totalBytes = 21_569_325) }
        repeat(3) { runWorker() }

        assertEquals("no further transfer after the full storage", listOf(0), edges.phoneCalls)
        assertEquals(listOf(TrackDownload.NONE, TrackDownload.QUEUED, TrackDownload.QUEUED), states(bookId))
        assertEquals(21_569_325L, state.fileSize(bookId, 0))
        assertEquals(WearDownloadError.STORAGE_FULL, state.lastError.value)
    }

    @Test
    fun `a Wi-Fi lost during the download is no server failure`() = runTest {
        val bookId = book(files = 1)
        edges.server = {
            edges.wifiUp = false
            DownloadOutcome.Retry(IOException("Software caused connection abort"))
        }
        repeat(3) { runWorker() }

        assertEquals(3, edges.serverCalls.size)
        assertTrue("never via the phone", edges.phoneCalls.isEmpty())
        assertFalse(state.hasServerFailedTooOften(failureKey(bookId)))
        assertTrue(state.waitingForWifi.value)
        assertEquals(listOf(TrackDownload.QUEUED), states(bookId))
    }

    @Test
    fun `without Wi-Fi nothing counts and the book waits`() = runTest {
        val bookId = book(files = 2)
        edges.wifi = false
        runWorker()

        assertTrue(edges.serverCalls.isEmpty())
        assertTrue(edges.phoneCalls.isEmpty())
        assertTrue(state.waitingForWifi.value)
        assertTrue(retryScheduled())
        assertEquals(List(2) { TrackDownload.QUEUED }, states(bookId))
    }

    @Test
    fun `a book without key on the watch counts as failed attempts, then comes via the phone`() = runTest {
        val bookId = book(files = 1)
        edges.bookServerKnown = false
        repeat(3) { runWorker() }

        assertTrue("no server request without key", edges.serverCalls.isEmpty())
        assertEquals(listOf(0), edges.phoneCalls)
        assertEquals(listOf(TrackDownload.DONE), states(bookId))
    }

    private suspend fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<WearDownloadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    WearDownloadWorker(appContext, workerParameters, entryPoint)
            })
            .build()
            .doWork()

    private fun retryScheduled(): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("wear-downloads").get().any { it.state == WorkInfo.State.ENQUEUED }

    private suspend fun book(files: Int): Long {
        val bookId = bookDao.insertBook(
            BookEntity(serverId = "abs", remoteId = "li-1", title = "Book", normTitle = "book", author = "Author", durationMs = files * 60_000L),
        )
        bookDao.insertTracks(
            List(files) { index ->
                BookTrackEntity(
                    bookId = bookId,
                    trackIndex = index,
                    remoteFileId = "ino-$index",
                    startMs = index * 60_000L,
                    durationMs = 60_000,
                    mimeType = "audio/mpeg",
                    downloadState = TrackDownload.QUEUED,
                )
            },
        )
        return bookId
    }

    private suspend fun states(bookId: Long) = bookDao.tracks(bookId).map { it.downloadState }

    private fun failureKey(bookId: Long) = "book:$bookId"

    /** Wi-Fi, server and phone; the transfers update the entries like the real ones ([de.kleinabersmart.earloft.core.download.BookDownloader], [PhoneFileFetcher]). */
    private inner class FakeEdges : WearDownloadEdges {
        var wifi = true
        var wifiUp = true
        var bookServerKnown = true
        var server: (BookTrackEntity) -> DownloadOutcome = { DownloadOutcome.Done }
        var phone: (BookTrackEntity) -> DownloadOutcome = { DownloadOutcome.Done }
        val serverCalls = mutableListOf<Int>()
        val phoneCalls = mutableListOf<Int>()

        override suspend fun acquireWifi(): WifiLease? {
            if (!wifi) return null
            wifiUp = true
            return WifiLease(ShadowNetwork.newInstance(1), close = {}, isUp = { wifiUp })
        }

        override suspend fun downloadBookTrack(track: BookTrackEntity, network: Network, onProgress: TransferProgress): DownloadOutcome {
            serverCalls += track.trackIndex
            if (bookDao.markBookTrackRunning(track.bookId, track.trackIndex) == 0) return DownloadOutcome.Cancelled
            return server(track).also { if (it == DownloadOutcome.Done) done(track) }
        }

        override suspend fun bookTrackFromPhone(track: BookTrackEntity, onProgress: TransferProgress): DownloadOutcome {
            phoneCalls += track.trackIndex
            bookDao.requeueBookTrack(track.bookId, track.trackIndex)
            bookDao.queueBookDownload(track.bookId)
            if (bookDao.markBookTrackRunning(track.bookId, track.trackIndex) == 0) return DownloadOutcome.Cancelled
            return when (val outcome = phone(track)) {
                DownloadOutcome.Done -> outcome.also { done(track) }
                is DownloadOutcome.NoSpace -> outcome.also { bookDao.unqueueTrack(track.bookId, track.trackIndex) }
                else -> outcome
            }
        }

        private suspend fun done(track: BookTrackEntity) {
            val file = bookStorage.fileFor(track.bookId, track.trackIndex, track.mimeType)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(16))
            bookDao.markBookTrackDone(track.bookId, track.trackIndex, file.absolutePath, file.length())
        }

        override suspend fun hasBookServer(serverId: String) = bookServerKnown
        override suspend fun expiringBookKeys(withinSeconds: Long) = emptyList<String>()
        override suspend fun requestCredentials(sourceIds: Collection<String>) = Unit
        override suspend fun announcePhoneWait() = Unit
        override suspend fun coverFromPhone(bookId: Long): ByteArray? = null
        override suspend fun ensureBookCover(bookId: Long) = true
        override suspend fun storeBookCover(bookId: Long, bytes: ByteArray) = Unit
        override suspend fun cacheAlbumCover(albumId: Long, sizePx: Int) = true

        override suspend fun knowsSource(uid: String) = error("No songs in these tests")
        override suspend fun downloadSong(download: DownloadEntity, profile: DownloadProfile, network: Network, onProgress: TransferProgress) =
            error("No songs in these tests")
        override suspend fun songFromPhone(download: DownloadEntity, onProgress: TransferProgress) = error("No songs in these tests")
        override suspend fun downloadEpisode(track: BookTrackEntity, network: Network, onProgress: TransferProgress) =
            error("No episodes in these tests")
        override suspend fun episodeFromPhone(track: BookTrackEntity, onProgress: TransferProgress) = error("No episodes in these tests")
    }
}
