package de.kleinabersmart.tinyamp.core.data.sync

import de.kleinabersmart.tinyamp.core.data.sync.LocalScanStamp.Companion.scanNeeded
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScanStampTest {

    @Test
    fun `unveraenderter MediaStore braucht keinen Scan`() {
        assertEquals(false, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v1:42"))
    }

    @Test
    fun `neue Generation oder neue Version brauchen einen Scan`() {
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v1:43"))
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "external_primary:v2:1"))
    }

    @Test
    fun `eine neue Speicherkarte braucht einen Scan`() {
        assertEquals(true, scanNeeded(stored = "external_primary:v1:42", current = "1234-abcd:v9:3,external_primary:v1:42"))
    }

    @Test
    fun `noch nie gescannt braucht einen Scan`() {
        assertEquals(true, scanNeeded(stored = null, current = "external_primary:v1:42"))
    }

    @Test
    fun `ohne Generation ist es unbekannt`() {
        assertEquals(null, scanNeeded(stored = "external_primary:v1:42", current = null))
        assertEquals(null, scanNeeded(stored = null, current = null))
    }
}
