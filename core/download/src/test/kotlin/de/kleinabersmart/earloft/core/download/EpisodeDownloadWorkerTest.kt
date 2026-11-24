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
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs of the [EpisodeDownloadWorker] with the real database, files and [DownloadSpace]; only the feed hosts are a fake.
 * Covers the two parallel downloads, running out of room and the retry budget (review2 section 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EpisodeDownloadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.podcastDao()
    private val storage = EpisodeStorage(context)
    private val space = DownloadSpace(context)
    private val edges = FakeEdges()

    private val entryPoint = object : EpisodeDownloadWorker.EpisodeEntryPoint {
        override fun podcastDao() = dao
        override fun episodeStorage() = storage
        override fun episodeProgress() = EpisodeDownloadProgress()
        override fun space() = space
        override fun episodeEdges(): EpisodeDownloadEdges = edges
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
    fun `newest first, two at a time, every episode once`() = runBlocking {
        // Real time: with the virtual time of runTest the first download would end before the other run took its episode.
        val old = episode(addedAt = 1)
        val middle = episode(addedAt = 2)
        val new = episode(addedAt = 3)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(setOf(new, middle), edges.calls.take(2).toSet())
        assertEquals(listOf(new, middle, old).sorted(), edges.calls.sorted())
        assertEquals(2, edges.mostAtOnce.get())
        assertEquals(listOf(new, middle, old).sorted(), edges.downloaded.sorted())
        assertEquals(List(3) { TrackDownload.DONE }, states(old, middle, new))
    }

    @Test
    fun `an episode that doesn't fit stops the run and leaves the rest to the space retry`() = runTest {
        val old = episode(addedAt = 1)
        val middle = episode(addedAt = 2)
        val new = episode(addedAt = 3)
        edges.server = { bookId ->
            if (bookId == new) {
                DownloadOutcome.NoSpace(totalBytes = 900_000_000)
            } else {
                // The parallel run ends only once the full storage is known; before that it may rightly take the next one.
                space.isShort.first { it }
                DownloadOutcome.Done
            }
        }

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue("no new episode after running out of room", old !in edges.calls)
        assertEquals(TrackDownload.QUEUED, states(new).single())
        assertEquals(TrackDownload.QUEUED, states(old).single())
        assertTrue(space.isShort.value)
        assertTrue(enqueued("download-space-retry-EPISODES"))
        assertTrue("the parallel one may finish", states(middle).single() in setOf(TrackDownload.DONE, TrackDownload.QUEUED))
    }

    @Test
    fun `an interrupted episode is queued again and the run retried`() = runTest {
        val only = episode(addedAt = 1)
        edges.server = { DownloadOutcome.Retry(IOException("Connection reset")) }

        assertEquals(ListenableWorker.Result.retry(), runWorker(attempt = 0))

        assertEquals(listOf(only), edges.calls)
        assertEquals(TrackDownload.QUEUED, states(only).single())
    }

    @Test
    fun `in the last attempt only one interrupted episode gives up, the other waits`() = runTest {
        val first = episode(addedAt = 2)
        val second = episode(addedAt = 1)
        edges.server = { DownloadOutcome.Retry(IOException("Connection reset")) }

        assertEquals(ListenableWorker.Result.retry(), runWorker(attempt = 4))

        // Which of the two parallel ones comes back first is up to the network.
        assertEquals(listOf(TrackDownload.FAILED, TrackDownload.QUEUED), states(first, second).sortedBy { it.name })
    }

    private suspend fun runWorker(attempt: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder<EpisodeDownloadWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    EpisodeDownloadWorker(appContext, workerParameters, entryPoint)
            })
            .build()
            .doWork()

    private fun enqueued(uniqueName: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().any { it.state == WorkInfo.State.ENQUEUED }

    private suspend fun episode(addedAt: Long): Long {
        val bookId = db.bookDao().insertBook(
            BookEntity(
                serverId = "feed",
                kind = BookKind.EPISODE,
                remoteId = "guid-$addedAt",
                title = "Episode $addedAt",
                normTitle = "episode $addedAt",
                author = "Host",
                durationMs = 60_000,
                addedAt = addedAt,
            ),
        )
        db.bookDao().insertTracks(
            listOf(
                BookTrackEntity(
                    bookId = bookId,
                    trackIndex = 0,
                    remoteFileId = "https://feed.example/$addedAt.mp3",
                    startMs = 0,
                    durationMs = 60_000,
                    mimeType = "audio/mpeg",
                    downloadState = TrackDownload.QUEUED,
                ),
            ),
        )
        return bookId
    }

    private suspend fun states(vararg bookIds: Long) = bookIds.map { db.bookDao().tracks(it).single().downloadState }

    /** The feed hosts; like [EpisodeDownloader] they mark the episode running and, when done, downloaded. */
    private inner class FakeEdges : EpisodeDownloadEdges {
        var server: suspend (Long) -> DownloadOutcome = { DownloadOutcome.Done }
        val calls: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
        val downloaded: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
        val mostAtOnce = AtomicInteger()
        private val atOnce = AtomicInteger()

        override suspend fun download(track: BookTrackEntity, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
            calls += track.bookId
            if (dao.markDownloadRunning(track.bookId) == 0) return DownloadOutcome.Cancelled
            mostAtOnce.accumulateAndGet(atOnce.incrementAndGet(), ::maxOf)
            try {
                // Long enough that the other run takes its episode meanwhile.
                delay(100)
            } finally {
                atOnce.decrementAndGet()
            }
            val outcome = server(track.bookId)
            if (outcome == DownloadOutcome.Done) {
                val file = storage.fileFor(track.bookId, track.mimeType)
                file.parentFile?.mkdirs()
                file.writeBytes(ByteArray(16))
                dao.markDownloadDone(track.bookId, file.absolutePath, file.length())
            }
            return outcome
        }

        override suspend fun downloaded(bookId: Long) {
            downloaded += bookId
        }
    }
}
