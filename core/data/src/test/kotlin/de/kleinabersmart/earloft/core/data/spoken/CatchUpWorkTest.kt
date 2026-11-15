package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatchUpWorkTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `scheduling again keeps the waiting job instead of replacing it`() {
        BookProgressWorker.schedule(context)
        val first = pending("book-progress").single()
        BookProgressWorker.schedule(context)
        assertEquals(first.id, pending("book-progress").single().id)

        AbsSessionWorker.schedule(context)
        val session = pending("abs-sessions").single()
        AbsSessionWorker.schedule(context)
        assertEquals(session.id, pending("abs-sessions").single().id)
    }

    @Test
    fun `a user action replaces the waiting job so it goes out now`() {
        BookProgressWorker.schedule(context)
        val first = pending("book-progress").single()
        BookProgressWorker.schedule(context, replace = true)
        assertNotEquals(first.id, pending("book-progress").single().id)
    }

    @Test
    fun `retries while something is pending and gives up after the last attempt`() {
        assertTrue(CatchUpWork.result(done = true, runAttemptCount = 0) is ListenableWorker.Result.Success)
        assertTrue(CatchUpWork.result(done = false, runAttemptCount = 0) is ListenableWorker.Result.Retry)
        assertTrue(CatchUpWork.result(done = false, runAttemptCount = CatchUpWork.MAX_ATTEMPTS - 2) is ListenableWorker.Result.Retry)
        assertTrue(CatchUpWork.result(done = false, runAttemptCount = CatchUpWork.MAX_ATTEMPTS - 1) is ListenableWorker.Result.Success)
    }

    private fun pending(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
}
