package de.kleinabersmart.earloft.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkBackoffTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `a manual download is appended while nothing waits or the job waits for its first run`() = runTest {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workManager.manualDownloadPolicy(NAME))

        val waitingForWifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RetryWorker>().setConstraints(waitingForWifi).build())

        assertFalse(workManager.waitsInBackoff(NAME))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workManager.manualDownloadPolicy(NAME))
    }

    @Test
    fun `a manual download replaces a job that waits in backoff after a failed run`() = runTest {
        // Ohne Bedingungen läuft er im Test sofort, scheitert und wartet dann mit Backoff.
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RetryWorker>().build())

        assertTrue(workManager.waitsInBackoff(NAME))
        assertEquals(ExistingWorkPolicy.REPLACE, workManager.manualDownloadPolicy(NAME))
    }

    class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.retry()
    }

    private companion object {
        const val NAME = "test-downloads"
    }
}
