package de.kleinabersmart.earloft.wear.download

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchStateTest {

    private val state = WatchState(ApplicationProvider.getApplicationContext())

    @Test
    fun `a song switches to the phone after three server failures`() {
        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(1, state.recordServerFailure("nd:1"))
        assertEquals(2, state.recordServerFailure("nd:1"))
        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(3, state.recordServerFailure("nd:1"))
        assertTrue(state.hasServerFailedTooOften("nd:1"))
    }

    @Test
    fun `a full storage error goes once there is room, other errors stay`() {
        state.setStorageFullError()
        assertEquals(WearDownloadError.STORAGE_FULL, WatchState(ApplicationProvider.getApplicationContext()).lastError.value)
        state.clearStorageFullError()
        assertEquals(null, state.lastError.value)

        state.setStorageFullError()
        state.setLastError(WearDownloadError.PHONE_UNREACHABLE)
        state.clearStorageFullError()
        assertEquals("a later error replaces the full storage", WearDownloadError.PHONE_UNREACHABLE, state.lastError.value)
    }

    @Test
    fun `a full storage stored by an older version still counts after the update`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("watch_state", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("last_error_storage_full", true).putString("last_error", "Speicher voll").commit()

        val updated = WatchState(context)
        assertTrue(updated.isStorageFull)
        updated.clearStorageFullError()
        assertEquals(null, WatchState(context).lastError.value)
    }

    @Test
    fun `retry runs count up and start over after a clean end`() {
        assertEquals(0, state.retryRuns)
        assertEquals(1, state.recordRetry())
        assertEquals(2, state.recordRetry())
        assertEquals(2, WatchState(ApplicationProvider.getApplicationContext()).retryRuns)
        state.clearRetries()
        assertEquals(0, state.retryRuns)
        assertEquals(1, state.recordRetry())
    }

    @Test
    fun `a book keeps coming over the phone until its last file arrived`() {
        repeat(WatchState.SERVER_ATTEMPTS) { state.recordServerFailure("book:7") }
        state.recordRetry()
        state.recordRetry()

        state.deliveredViaPhone("book:7", moreToCome = true)
        assertTrue("the next file skips the server", state.hasServerFailedTooOften("book:7"))
        assertEquals("progress restarts the interval", 0, state.retryRuns)

        state.deliveredViaPhone("book:7", moreToCome = false)
        assertFalse("a later book file asks the server again", state.hasServerFailedTooOften("book:7"))
    }

    @Test
    fun `an unreachable server counts for all waiting songs of it`() {
        state.recordServerFailure("nd:1")
        state.recordServerFailures(listOf("nd:2", "nd:3"))
        state.recordServerFailures(listOf("nd:2", "nd:3"))

        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertFalse(state.hasServerFailedTooOften("nd:2"))
        assertEquals(3, state.recordServerFailure("nd:2"))
        assertTrue(state.hasServerFailedTooOften("nd:2"))
    }

    @Test
    fun `songs marked for the phone count for the backlog but not as server failures`() {
        assertFalse(state.isMarkedViaPhone("nd:1"))

        state.markViaPhone(listOf("nd:1", "nd:2"))

        assertTrue(state.isMarkedViaPhone("nd:1"))
        assertTrue(state.isMarkedViaPhone("nd:2"))
        assertFalse(state.isMarkedViaPhone("nd:3"))
        // The server is still asked first.
        assertFalse(state.hasServerFailedTooOften("nd:1"))
    }

    @Test
    fun `a finished download and retry clear the phone mark`() {
        state.markViaPhone(listOf("nd:1", "nd:2"))

        state.clearServerFailures("nd:1")
        assertFalse(state.isMarkedViaPhone("nd:1"))
        assertTrue(state.isMarkedViaPhone("nd:2"))

        state.clearAllServerFailures()
        assertFalse(state.isMarkedViaPhone("nd:2"))
    }

    @Test
    fun `a finished download clears the failures`() {
        repeat(3) { state.recordServerFailure("nd:1") }

        state.clearServerFailures("nd:1")

        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(1, state.recordServerFailure("nd:1"))
    }

    @Test
    fun `a job that waits for the charger is remembered across a restart until it runs or is replaced`() {
        assertFalse(state.jobOnCharger.value)
        state.setJobOnCharger(true)
        assertTrue(WatchState(ApplicationProvider.getApplicationContext()).jobOnCharger.value)
        state.setJobOnCharger(false)
        assertFalse(WatchState(ApplicationProvider.getApplicationContext()).jobOnCharger.value)
    }
}
