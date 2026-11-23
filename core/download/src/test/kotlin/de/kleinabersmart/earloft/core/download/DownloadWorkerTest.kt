package de.kleinabersmart.earloft.core.download

import android.content.Context
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
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.network.HttpStatusException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Runs of the music [DownloadWorker] with the real database, files and [DownloadSpace]; only the servers are a fake. Covers
 * the retry budget, rejected credentials and running out of room (review2 section 5).
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val library = TestLibrary()
    private val dao = library.db.downloadDao()
    private val storage = DownloadStorage(context)
    private val space = DownloadSpace(context)
    private val edges = FakeEdges()

    private val entryPoint = object : DownloadWorker.DownloadEntryPoint {
        override fun downloadDao() = dao
        override fun storage() = storage
        override fun space() = space
        override fun musicEdges(): MusicDownloadEdges = edges
    }

    @Before
    fun setUp() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        library.source("nd")
        library.source("jf")
        val album = library.album("Mondlicht")
        for ((index, uid) in listOf("nd:1", "jf:2", "nd:3").withIndex()) {
            val (sourceId, remoteId) = uid.split(':')
            val songId = library.song(album, "Song $index", sourceId to remoteId)
            dao.insertIfAbsent(listOf(DownloadEntity(uid, songId, DownloadState.QUEUED)))
        }
    }

    @After
    fun tearDown() {
        library.close()
    }

    @Test
    fun `the queue is worked through in order and the covers cached`() = runTest {
        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(listOf("nd:1", "jf:2", "nd:3"), edges.calls)
        assertEquals(List(3) { DownloadState.DONE }, states())
        assertEquals(1, edges.covers)
    }

    @Test
    fun `an interrupted song is queued again and the run retried`() = runTest {
        edges.server = { uid -> if (uid == "jf:2") DownloadOutcome.Retry(IOException("Connection reset")) else DownloadOutcome.Done }

        assertEquals(ListenableWorker.Result.retry(), runWorker(attempt = 0))

        assertEquals(listOf("nd:1", "jf:2"), edges.calls)
        assertEquals(listOf(DownloadState.DONE, DownloadState.QUEUED, DownloadState.QUEUED), states())
    }

    @Test
    fun `in the last attempt the interrupted song gives up and the queue goes on`() = runTest {
        edges.server = { uid -> if (uid == "jf:2") DownloadOutcome.Retry(IOException("Connection reset")) else DownloadOutcome.Done }

        assertEquals(ListenableWorker.Result.success(), runWorker(attempt = 4))

        assertEquals(listOf(DownloadState.DONE, DownloadState.FAILED, DownloadState.DONE), states())
    }

    @Test
    fun `rejected credentials fail the waiting songs of that server only`() = runTest {
        edges.server = { uid -> if (uid == "nd:1") DownloadOutcome.Retry(HttpStatusException(401)) else DownloadOutcome.Done }

        assertEquals(ListenableWorker.Result.success(), runWorker(attempt = 0))

        assertEquals("no request per song of the refusing server", listOf("nd:1", "jf:2"), edges.calls)
        assertEquals(listOf(DownloadState.FAILED, DownloadState.DONE, DownloadState.FAILED), states())
    }

    @Test
    fun `a song that doesn't fit stops the queue and leaves it to the space retry`() = runTest {
        edges.server = { DownloadOutcome.NoSpace(totalBytes = 80_000_000) }

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(listOf("nd:1"), edges.calls)
        assertEquals(List(3) { DownloadState.QUEUED }, states())
        assertTrue(space.isShort.value)
        assertTrue(enqueued("download-space-retry-MUSIC"))
    }

    private suspend fun runWorker(attempt: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    DownloadWorker(appContext, workerParameters, entryPoint)
            })
            .build()
            .doWork()

    private fun enqueued(uniqueName: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().any { it.state == WorkInfo.State.ENQUEUED }

    private suspend fun states() = listOf("nd:1", "jf:2", "nd:3").map { dao.get(it)!!.state }

    /** The servers; like [Downloader] they mark the song running and, when done, downloaded. */
    private inner class FakeEdges : MusicDownloadEdges {
        var server: (String) -> DownloadOutcome = { DownloadOutcome.Done }
        val calls = mutableListOf<String>()
        var covers = 0

        override suspend fun download(download: DownloadEntity, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
            val uid = download.sourceUid
            calls += uid
            if (dao.markRunning(uid) == 0) return DownloadOutcome.Cancelled
            val outcome = server(uid)
            if (outcome == DownloadOutcome.Done) {
                val file = storage.fileFor(uid, "audio/flac")
                file.parentFile?.mkdirs()
                file.writeBytes(ByteArray(16))
                dao.markDone(uid, file.absolutePath, file.length())
            }
            return outcome
        }

        override suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean {
            covers++
            return true
        }
    }
}
