package de.kleinabersmart.kleinaberlaut.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `terms become lowercase prefix queries`() {
        assertEquals("sigur* rós*", FtsQuery.build("Sigur Rós"))
    }

    @Test
    fun `fts operators and quotes are stripped`() {
        assertEquals("ac* dc* or* not*", FtsQuery.build("\"AC/DC\" OR NOT*"))
    }

    @Test
    fun `blank input yields null`() {
        assertNull(FtsQuery.build("  -- "))
    }
}
