package de.kleinabersmart.earloft.wear.download

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRecheckDelayTest {

    private val minute = 60_000L

    @Test
    fun `the first look waits ten minutes`() {
        assertEquals(10 * minute, storageRecheckDelayMs(0))
        assertEquals(10 * minute, storageRecheckDelayMs(-1))
        assertEquals(10 * minute, storageRecheckDelayMs(5 * minute))
    }

    @Test
    fun `each further wait doubles`() {
        assertEquals(20 * minute, storageRecheckDelayMs(10 * minute))
        assertEquals(40 * minute, storageRecheckDelayMs(20 * minute))
        assertEquals(320 * minute, storageRecheckDelayMs(160 * minute))
    }

    @Test
    fun `the wait stops at six hours`() {
        assertEquals(360 * minute, storageRecheckDelayMs(320 * minute))
        assertEquals(360 * minute, storageRecheckDelayMs(360 * minute))
        assertEquals(360 * minute, storageRecheckDelayMs(Long.MAX_VALUE / 2))
    }
}
