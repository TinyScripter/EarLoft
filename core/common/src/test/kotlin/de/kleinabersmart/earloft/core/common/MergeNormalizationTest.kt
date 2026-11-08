package de.kleinabersmart.earloft.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MergeNormalizationTest {

    @Test
    fun `ignores case, whitespace and punctuation`() {
        assertEquals("thebeatles", "  The Beatles ".normalizeForMerge())
        assertEquals("acdc", "AC/DC".normalizeForMerge())
    }

    @Test
    fun `removes diacritics so spellings merge`() {
        assertEquals("Bjork".normalizeForMerge(), "Björk".normalizeForMerge())
        assertEquals("sigurros", "Sigur Rós".normalizeForMerge())
    }

    @Test
    fun `keeps non latin scripts`() {
        assertEquals("東京事変", "東京事変".normalizeForMerge())
        assertEquals("кино", "Кино".normalizeForMerge())
    }

    @Test
    fun `different non latin titles do not collide`() {
        assertNotEquals("東京事変".normalizeForMerge(), "椎名林檎".normalizeForMerge())
    }

    @Test
    fun `keeps digits`() {
        assertEquals("blink182", "blink-182".normalizeForMerge())
    }
}
