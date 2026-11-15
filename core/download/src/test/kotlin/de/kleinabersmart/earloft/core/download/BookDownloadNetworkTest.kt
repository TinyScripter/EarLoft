package de.kleinabersmart.earloft.core.download

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDownloadNetworkTest {

    @Test
    fun `without the switch every download waits for Wi-Fi`() {
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = false, automatic = false))
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = false, automatic = true))
    }

    @Test
    fun `with the switch only a download the user started may use mobile data`() {
        assertEquals(NetworkType.CONNECTED, bookDownloadNetwork(mobileAllowed = true, automatic = false))
        assertEquals(NetworkType.UNMETERED, bookDownloadNetwork(mobileAllowed = true, automatic = true))
    }
}
