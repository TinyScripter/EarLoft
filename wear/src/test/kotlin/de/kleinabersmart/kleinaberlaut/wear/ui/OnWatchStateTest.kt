package de.kleinabersmart.kleinaberlaut.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnWatchStateTest {

    @Test
    fun `download, then progress, then remove`() {
        assertEquals(OnWatchStatus.NOT_ON_WATCH, onWatchState(confirmed = false, wanted = null, progress = null, failed = false).status)

        // Getippt, das Manifest ist noch unterwegs: lädt, ohne Prozent.
        val requested = onWatchState(confirmed = false, wanted = true, progress = null, failed = false)
        assertEquals(OnWatchStatus.DOWNLOADING, requested.status)
        assertNull(requested.progress)

        val loading = onWatchState(confirmed = true, wanted = null, progress = 0.23f, failed = false)
        assertEquals(OnWatchStatus.DOWNLOADING, loading.status)
        assertEquals(0.23f, loading.progress)

        assertEquals(OnWatchStatus.ON_WATCH, onWatchState(confirmed = true, wanted = null, progress = 1f, failed = false).status)
        assertEquals(OnWatchStatus.REMOVING, onWatchState(confirmed = true, wanted = false, progress = 1f, failed = false).status)
    }

    @Test
    fun `a failed download is only reported while it keeps the button below 100 percent`() {
        assertEquals(true, onWatchState(confirmed = true, wanted = null, progress = 0f, failed = false, downloadFailed = true).downloadFailed)
        assertEquals(false, onWatchState(confirmed = true, wanted = false, progress = 0f, failed = false, downloadFailed = true).downloadFailed)
    }
}
