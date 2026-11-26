package de.kleinabersmart.earloft.wear.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review4 T58: after the hint "Continues on the charger" turning on Wi-Fi has to start the downloads again. */
class ReplacesChargerJobTest {

    private fun replaces(
        jobOnCharger: Boolean = true,
        waitingForPhone: Boolean = false,
        requireCharging: Boolean = false,
        charging: Boolean = false,
        wifiOn: Boolean = true,
    ) = replacesChargerJob(jobOnCharger, waitingForPhone, requireCharging, charging, wifiOn)

    @Test
    fun `a job waiting for the charger is replaced once Wi-Fi is on`() {
        assertTrue(replaces())
    }

    @Test
    fun `without Wi-Fi the job keeps waiting for the charger`() {
        assertFalse(replaces(wifiOn = false))
    }

    @Test
    fun `a normal job is left alone`() {
        assertFalse(replaces(jobOnCharger = false))
    }

    @Test
    fun `with the phone's setting only on the charger a normal job would wait just the same`() {
        assertFalse(replaces(requireCharging = true))
    }

    @Test
    fun `a job waiting for the phone is left alone, Wi-Fi does not bring the phone back`() {
        // Each replacement would start the foreground service and fail on the phone right away (review V1).
        assertFalse(replaces(waitingForPhone = true))
    }

    @Test
    fun `on the charger a delayed retry is replaced too, it may wait for hours`() {
        assertTrue(replaces(charging = true))
        // On the charger the normal job may run even with the phone's setting "only on the charger".
        assertTrue(replaces(charging = true, requireCharging = true))
    }
}
