package de.kleinabersmart.earloft.wear.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review4 T58: after the hint "Continues on the charger" turning on Wi-Fi has to start the downloads again. */
class ReplacesChargerJobTest {

    @Test
    fun `a job waiting for the charger is replaced once Wi-Fi is on`() {
        assertTrue(replacesChargerJob(jobOnCharger = true, requireCharging = false, charging = false, wifiOn = true))
    }

    @Test
    fun `without Wi-Fi the job keeps waiting for the charger`() {
        assertFalse(replacesChargerJob(jobOnCharger = true, requireCharging = false, charging = false, wifiOn = false))
    }

    @Test
    fun `a normal job is left alone`() {
        assertFalse(replacesChargerJob(jobOnCharger = false, requireCharging = false, charging = false, wifiOn = true))
    }

    @Test
    fun `with the phone's setting only on the charger a normal job would wait just the same`() {
        assertFalse(replacesChargerJob(jobOnCharger = true, requireCharging = true, charging = false, wifiOn = true))
    }

    @Test
    fun `on the charger the job starts anyway`() {
        assertFalse(replacesChargerJob(jobOnCharger = true, requireCharging = false, charging = true, wifiOn = true))
    }
}
