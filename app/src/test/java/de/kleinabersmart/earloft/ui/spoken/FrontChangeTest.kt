package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontChangeTest {

    @Test
    fun `same first or empty rows change nothing`() {
        assertEquals(FrontChange.NONE, frontChange(listOf(1, 2, 3), listOf(1, 3, 2)))
        assertEquals(FrontChange.NONE, frontChange(emptyList(), listOf(1, 2)))
        assertEquals(FrontChange.NONE, frontChange(listOf(1, 2), emptyList()))
    }

    @Test
    fun `dropped first lets the second slide forward`() {
        assertEquals(FrontChange.NONE, frontChange(listOf(1, 2, 3), listOf(2, 3)))
    }

    @Test
    fun `second started in the row moves forward`() {
        assertEquals(FrontChange.MOVED, frontChange(listOf(1, 2, 3), listOf(2, 1, 3)))
    }

    @Test
    fun `later or new entries in front move forward`() {
        assertEquals(FrontChange.MOVED, frontChange(listOf(1, 2, 3), listOf(3, 1, 2)))
        assertEquals(FrontChange.MOVED, frontChange(listOf(1, 2, 3), listOf(9, 1, 2, 3)))
    }

    @Test
    fun `other first without the old one replaces the front`() {
        assertEquals(FrontChange.REPLACED, frontChange(listOf(1, 2, 3), listOf(3)))
        assertEquals(FrontChange.REPLACED, frontChange(listOf(1, 2), listOf(9, 2)))
    }

    @Test
    fun `a shelf appearing above the first one moves forward, its disappearing does not`() {
        assertEquals(FrontChange.MOVED, frontChange(listOf("recent", "subscriptions"), listOf("continue", "recent", "subscriptions")))
        assertEquals(FrontChange.NONE, frontChange(listOf("continue", "recent"), listOf("recent", "subscriptions")))
    }
}
