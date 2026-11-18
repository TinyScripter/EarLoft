package de.kleinabersmart.earloft.wear.download

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneWaitStepTest {

    @Test
    fun `the first runs without a phone retry with backoff`() {
        for (runs in 1 until WatchState.PHONE_ATTEMPTS) {
            assertEquals(PhoneWaitStep.RETRY, phoneWaitStep(runs, pluggedIn = false))
            assertEquals(PhoneWaitStep.RETRY, phoneWaitStep(runs, pluggedIn = true))
        }
    }

    @Test
    fun `off the charger the job then waits for the charger`() {
        assertEquals(PhoneWaitStep.WAIT_FOR_CHARGER, phoneWaitStep(WatchState.PHONE_ATTEMPTS, pluggedIn = false))
        assertEquals(PhoneWaitStep.WAIT_FOR_CHARGER, phoneWaitStep(WatchState.PHONE_ATTEMPTS + 3, pluggedIn = false))
    }

    @Test
    fun `on the charger it keeps the backoff instead of starting a new job right away`() {
        assertEquals(PhoneWaitStep.RETRY_ON_CHARGER, phoneWaitStep(WatchState.PHONE_ATTEMPTS, pluggedIn = true))
        assertEquals(PhoneWaitStep.RETRY_ON_CHARGER, phoneWaitStep(WatchState.PHONE_ATTEMPTS + 10, pluggedIn = true))
    }
}
