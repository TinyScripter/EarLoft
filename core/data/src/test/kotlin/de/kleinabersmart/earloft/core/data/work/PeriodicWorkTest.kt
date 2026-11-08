package de.kleinabersmart.earloft.core.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PeriodicWorkTest {

    private lateinit var workManager: WorkManager

    private val wifi = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `gleicher Auftrag beim naechsten Prozessstart bleibt unberuehrt`() = runTest {
        assertTrue(schedule(12, wifi))
        assertFalse(schedule(12, wifi))
        assertFalse(schedule(12, wifi))
        assertEquals(0, info().generation)
    }

    @Test
    fun `geaenderte Bedingungen ersetzen den Auftrag`() = runTest {
        schedule(12, wifi)
        val mobile = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        assertTrue(schedule(12, mobile))
        assertEquals(1, info().generation)
        assertEquals(NetworkType.CONNECTED, info().constraints.requiredNetworkType)
    }

    @Test
    fun `geaenderter Takt ersetzt den Auftrag`() = runTest {
        schedule(12, wifi)
        assertTrue(schedule(24, wifi))
        assertEquals(TimeUnit.HOURS.toMillis(24), info().periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `abgebrochener Auftrag wird neu eingeplant`() = runTest {
        schedule(12, wifi)
        workManager.cancelUniqueWork(NAME).result.get()
        assertTrue(schedule(12, wifi))
        assertEquals(WorkInfo.State.ENQUEUED, info().state)
    }

    private suspend fun schedule(hours: Long, constraints: Constraints): Boolean =
        workManager.ensurePeriodicWork(NAME, NoopWorker::class.java, hours, TimeUnit.HOURS, constraints)

    private suspend fun info(): WorkInfo =
        workManager.getWorkInfosForUniqueWorkFlow(NAME).first().single { !it.state.isFinished }

    class NoopWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

    private companion object {
        const val NAME = "test-periodic"
    }
}
