package de.kleinabersmart.earloft.wear.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review4 T58: after the hint "Continues on the charger" turning on Wi-Fi has to start the downloads again. */
class ReplacesChargerJobTest {

    private fun replaces(
        jobOnCharger: Boolean = true,
        jobWaitsForWifi: Boolean = true,
        waitingForPhone: Boolean = false,
        requireCharging: Boolean = false,
        charging: Boolean = false,
        wifiOn: Boolean = true,
    ) = replacesChargerJob(jobOnCharger, jobWaitsForWifi, waitingForPhone, requireCharging, charging, wifiOn)

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
    fun `a job waiting out a server error keeps its backoff, on the charger and off it`() {
        // review5 P65: replacing reset the backoff, and after three quick failures the songs came over Bluetooth.
        assertFalse(replaces(jobWaitsForWifi = false))
        assertFalse(replaces(jobWaitsForWifi = false, charging = true))
    }

    @Test
    fun `on the charger a delayed retry for Wi-Fi is replaced too, it may wait for hours`() {
        assertTrue(replaces(charging = true))
        // On the charger the normal job may run even with the phone's setting "only on the charger".
        assertTrue(replaces(charging = true, requireCharging = true))
    }
}
