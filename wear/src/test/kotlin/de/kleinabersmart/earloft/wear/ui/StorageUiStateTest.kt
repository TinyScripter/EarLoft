package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.model.DownloadOverview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUiStateTest {

    private fun state(pending: Int, requireCharging: Boolean, charging: Boolean, noWifi: Boolean = false) = StorageUiState(
        overview = DownloadOverview(0, pending, 0, 0, 0, null),
        requireCharging = requireCharging,
        charging = charging,
        noWifi = noWifi,
    )

    @Test
    fun `wartet auf das Ladegerät, wenn etwas ansteht und die Uhr nicht lädt`() {
        assertTrue(state(pending = 3, requireCharging = true, charging = false).waitingForCharger)
    }

    @Test
    fun `wartet nicht, wenn die Uhr lädt`() {
        assertFalse(state(pending = 3, requireCharging = true, charging = true).waitingForCharger)
    }

    @Test
    fun `wartet nicht, wenn das Handy kein Ladegerät verlangt`() {
        assertFalse(state(pending = 3, requireCharging = false, charging = false).waitingForCharger)
    }

    @Test
    fun `wartet auf WLAN, wenn etwas ansteht und der letzte Lauf kein WLAN bekam`() {
        assertTrue(state(pending = 3, requireCharging = false, charging = false, noWifi = true).waitingForWifi)
    }

    @Test
    fun `wartet nicht auf WLAN, wenn nichts ansteht`() {
        assertFalse(state(pending = 0, requireCharging = false, charging = true, noWifi = true).waitingForWifi)
    }

    @Test
    fun `wartet zuerst auf das Ladegerät, nicht auf WLAN`() {
        assertFalse(state(pending = 3, requireCharging = true, charging = false, noWifi = true).waitingForWifi)
    }

    @Test
    fun `wartet nicht, wenn nichts ansteht`() {
        assertFalse(state(pending = 0, requireCharging = true, charging = false).waitingForCharger)
    }
}
