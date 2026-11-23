package de.kleinabersmart.earloft.wear.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file from the phone is checked against the storage before the first byte (review2 J7). */
class FitsForTransferTest {

    private val mb = 1024L * 1024

    @Test
    fun `a file fits as long as the reserve stays free`() {
        assertTrue(fitsForTransfer(missingBytes = 400 * mb, allocatableBytes = 500 * mb))
        assertTrue(fitsForTransfer(missingBytes = 400 * mb, allocatableBytes = 400 * mb + TRANSFER_RESERVE_BYTES))
    }

    @Test
    fun `a file that would eat into the reserve does not fit`() {
        assertFalse(fitsForTransfer(missingBytes = 401 * mb, allocatableBytes = 500 * mb))
        assertFalse(fitsForTransfer(missingBytes = 1, allocatableBytes = 50 * mb))
    }
}
