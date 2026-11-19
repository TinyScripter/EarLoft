package de.kleinabersmart.earloft.core.download

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
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
}
