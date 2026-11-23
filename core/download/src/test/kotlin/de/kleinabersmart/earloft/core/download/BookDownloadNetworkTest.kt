package de.kleinabersmart.earloft.core.download

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDownloadNetworkTest {

    @Test
    fun `without the switch every download waits for Wi-Fi`() {
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = false, manualWaiting = true))
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = false, manualWaiting = false))
    }

    @Test
    fun `with the switch only files the user queued may use mobile data`() {
        assertEquals(NetworkType.CONNECTED, bookDownloadNetwork(mobileAllowed = true, manualWaiting = true))
        // Only automation files: even when flipping the switch or at app start the job stays on Wi-Fi.
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = true, manualWaiting = false))
    }

    @Test
    fun `a mobile run hands the remaining files to a Wi-Fi job`() {
        assertTrue(handsOverAfterRun(manualOnly = true, pending = 3, noRoom = false))
        assertFalse(handsOverAfterRun(manualOnly = true, pending = 0, noRoom = false))
        // A Wi-Fi run has nothing to hand over: it already takes every file.
        assertFalse(handsOverAfterRun(manualOnly = false, pending = 3, noRoom = false))
    }

    @Test
    fun `no follow-up job after running out of room, the space retry takes over`() {
        assertFalse(handsOverAfterRun(manualOnly = true, pending = 3, noRoom = true))
    }
}
