package de.kleinabersmart.earloft.wear.download

import androidx.work.WorkInfo
import androidx.work.WorkInfo.State
import de.kleinabersmart.earloft.wear.download.WearDownloadScheduler.Companion.TAG_ON_CHARGER
import de.kleinabersmart.earloft.wear.download.WearDownloadScheduler.Companion.startsNextOnCharger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Whether the download job that starts next waits for the charger (review4 T58, 8.6 Y28). */
class NextJobOnChargerTest {

    private fun job(state: State, onCharger: Boolean = false) =
        WorkInfo(UUID.randomUUID(), state, if (onCharger) setOf(TAG_ON_CHARGER) else emptySet())

    @Test
    fun `a new selection appended before the job on the charger starts first`() {
        // The running job appended its continuation on the charger after a new selection had been appended to it.
        assertEquals(false, startsNextOnCharger(listOf(job(State.SUCCEEDED), job(State.ENQUEUED), job(State.BLOCKED, onCharger = true))))
    }

    @Test
    fun `once the jobs before it are done, the job on the charger is the next`() {
        // Before, the flag was cleared when the appended selection ran, and the watch said "Waiting for Wi-Fi" again.
        assertEquals(true, startsNextOnCharger(listOf(job(State.SUCCEEDED), job(State.SUCCEEDED), job(State.ENQUEUED, onCharger = true))))
    }

    @Test
    fun `nothing waits while a job runs or when none is left`() {
        assertEquals(false, startsNextOnCharger(listOf(job(State.RUNNING), job(State.BLOCKED, onCharger = true))))
        assertEquals(false, startsNextOnCharger(emptyList()))
        assertEquals(false, startsNextOnCharger(listOf(job(State.ENQUEUED))))
    }
}
