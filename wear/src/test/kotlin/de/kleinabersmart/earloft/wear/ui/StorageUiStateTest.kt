package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.model.DownloadOverview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUiStateTest {

    private fun state(pending: Int, requireCharging: Boolean, charging: Boolean, noWifi: Boolean = false, jobOnCharger: Boolean = false) =
        StorageUiState(
            overview = DownloadOverview(0, pending, 0, 0, 0, null),
            requireCharging = requireCharging,
            charging = charging,
            noWifi = noWifi,
            jobOnCharger = jobOnCharger,
        )

    @Test
    fun `waits for the charger when something is pending and the watch is not charging`() {
        assertTrue(state(pending = 3, requireCharging = true, charging = false).waitingForCharger)
    }

    @Test
    fun `does not wait when the watch is charging`() {
        assertFalse(state(pending = 3, requireCharging = true, charging = true).waitingForCharger)
    }

    @Test
    fun `does not wait when the phone does not require the charger`() {
        assertFalse(state(pending = 3, requireCharging = false, charging = false).waitingForCharger)
    }

    @Test
    fun `waits for Wi-Fi when something is pending and the last run got no Wi-Fi`() {
        assertTrue(state(pending = 3, requireCharging = false, charging = false, noWifi = true).waitingForWifi)
    }

    @Test
    fun `does not wait for Wi-Fi when nothing is pending`() {
        assertFalse(state(pending = 0, requireCharging = false, charging = true, noWifi = true).waitingForWifi)
    }

    @Test
    fun `waits for the charger first, not for Wi-Fi`() {
        assertFalse(state(pending = 3, requireCharging = true, charging = false, noWifi = true).waitingForWifi)
    }

    @Test
    fun `does not wait when nothing is pending`() {
        assertFalse(state(pending = 0, requireCharging = true, charging = false).waitingForCharger)
    }

    @Test
    fun `a job that only runs on the charger after repeated attempts asks for the charger, not for Wi-Fi`() {
        // review4 T58: turning on Wi-Fi changes nothing until the watch is on power.
        val waiting = state(pending = 3, requireCharging = false, charging = false, noWifi = true, jobOnCharger = true)
        assertTrue(waiting.waitingForCharger)
        assertFalse(waiting.waitingForWifi)
    }

    @Test
    fun `a job on the charger waits for nothing once the watch is charging`() {
        val charging = state(pending = 3, requireCharging = false, charging = true, noWifi = true, jobOnCharger = true)
        assertFalse(charging.waitingForCharger)
    }
}
