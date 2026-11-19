package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.data.sync.LocalScanStamp.Companion.scanNeeded
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScanStampTest {

    @Test
    fun `an unchanged MediaStore needs no scan`() {
        assertEquals(false, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v1:42"))
    }

    @Test
    fun `a new generation or a new version needs a scan`() {
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v1:43"))
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v2:1"))
    }

    @Test
    fun `a new memory card needs a scan`() {
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "1234-abcd:v9:3,external_primary:v1:42"))
    }

    @Test
    fun `never scanned needs a scan`() {
        assertEquals(true, scanNeeded(stored = null, current = "external_primary:v1:42"))
    }

    @Test
    fun `without a generation it is unknown`() {
        assertEquals(null, scanNeeded(stored = "external_primary:v1:42", current = null))
        assertEquals(null, scanNeeded(stored = null, current = null))
    }
}
