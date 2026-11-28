package de.kleinabersmart.earloft.wear.download

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new selection during a run appends at most one job (review5 P70). */
class AppendedJobTest {

    @Test
    fun `behind a running job one is appended only if none waits there yet`() {
        assertTrue(needsAppendedJob(listOf(RUNNING)))
        assertFalse(needsAppendedJob(listOf(RUNNING, BLOCKED)))
        assertFalse(needsAppendedJob(listOf(RUNNING, ENQUEUED)))
        // Nothing running: enqueue as before (replace a waiting one or start one).
        assertTrue(needsAppendedJob(listOf(ENQUEUED)))
        assertTrue(needsAppendedJob(listOf(SUCCEEDED)))
        assertTrue(needsAppendedJob(emptyList()))
    }
}
